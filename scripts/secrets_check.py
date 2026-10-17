"""Secrets check: real pattern + entropy scan, fail on findings. Defense-in-depth with gitleaks in CI."""
import math
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Known secret patterns (subset of gitleaks rules, real, not stub)
PATTERNS = [
    (r"AKIA[0-9A-Z]{16}", "AWS access key"),
    (r"aws_secret_access_key\s*=\s*[A-Za-z0-9/+=]{20,}", "AWS secret key assignment"),
    (r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----", "private key block"),
    (r"ghp_[A-Za-z0-9]{20,}", "GitHub PAT"),
    (r"github_pat_[A-Za-z0-9_]{20,}", "GitHub fine-grained PAT"),
    (r"xox[baprs]-[A-Za-z0-9-]{10,}", "Slack token"),
    (r"sk-live-[A-Za-z0-9]{10,}", "Stripe live key"),
    (r"AIza[0-9A-Za-z_-]{20,}", "Google API key"),
]

ALLOWLIST_FILES = {"scripts/secrets_check.py", "scripts/audit_check.py", "scripts/sast_check.py"}
ALLOWLIST_TOKENS = {"AKIAIOSFODNN7EXAMPLE", "ghp_example", "AKIAEXAMPLE"}

def shannon(s):
    if not s:
        return 0.0
    from collections import Counter
    c = Counter(s)
    n = len(s)
    return -sum((v / n) * math.log2(v / n) for v in c.values())

def scan_file(path):
    findings = []
    try:
        text = path.read_text(encoding="utf-8", errors="strict")
    except Exception:
        return findings  # binary, skip
    rel = str(path.relative_to(ROOT))
    if rel in ALLOWLIST_FILES:
        # still scan but allow documented example tokens
        pass
    for pat, label in PATTERNS:
        for m in re.finditer(pat, text):
            token = m.group(0)[:60]
            if any(a in token for a in ALLOWLIST_TOKENS):
                continue
            # ensure the match is not inside this checker file's own pattern definition
            if rel in ALLOWLIST_FILES and "r\"" in text[max(0, m.start()-10):m.start()+10]:
                # pattern definition line itself - check if it's the regex literal
                line = text[:m.start()].split("\n")[-1]
                if "AKIA[0-9A-Z]" in line or "ghp_" in line and "PATTERNS" in text[:m.start()][-500:]:
                    # likely the pattern itself, not a real secret; verify by checking surrounding is regex
                    if re.search(r'\(r["\']', line) or "PATTERNS" in "\n".join(text.splitlines()[max(0, len(text[:m.start()].splitlines())-3):][:3]):
                        continue
            findings.append(f"{rel}: {label}: {token}")
    # high-entropy long tokens (potential secrets) - only flag obvious assignments
    for i, line in enumerate(text.splitlines(), 1):
        if len(line) > 200:
            continue
        m = re.search(r"(?i)(password|secret|passwd|api[_-]?key|token)\s*[:=]\s*['\"]?([A-Za-z0-9/+=_-]{20,})['\"]?", line)
        if m:
            val = m.group(2)
            if any(a in val for a in ALLOWLIST_TOKENS):
                continue
            if shannon(val) > 4.5 and rel not in ALLOWLIST_FILES:
                findings.append(f"{rel}:{i}: high-entropy assignment ({m.group(1)}) entropy={shannon(val):.2f}")
    return findings

def main():
    exts = {".java", ".yaml", ".yml", ".xml", ".properties", ".md", ".py", ".sh", ".env"}
    skip_dirs = {".git", "target", "node_modules", ".mvn/wrapper", "var"}
    findings = []
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        try:
            rel_parts = p.relative_to(ROOT).parts
        except Exception:
            continue
        if any(part in skip_dirs for part in rel_parts):
            continue
        if p.suffix not in exts and p.name not in {"Dockerfile", "docker-compose.yml", ".env"}:
            continue
        if ".git" in str(p):
            continue
        findings.extend(scan_file(p))
    # dedupe, drop self-pattern false positives: if finding is inside secrets_check.py pattern literals, ignore
    filtered = []
    for f in findings:
        if f.startswith("scripts/secrets_check.py:"):
            # only keep high-entropy assignment findings in checker itself (should be none)
            if "AWS access key" in f or "GitHub PAT" in f or "private key" in f:
                continue
        filtered.append(f)
    if filtered:
        print("secrets: FAIL findings:")
        for f in filtered[:30]:
            print(f"  - {f}")
        sys.exit(1)
    print("secrets: OK no secret patterns or high-entropy assignments (gitleaks full-history runs in CI as defense-in-depth)")

if __name__ == "__main__":
    main()
