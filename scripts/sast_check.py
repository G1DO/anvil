"""SAST check: real Java static-analysis patterns, fail on findings. CodeQL runs in CI as well."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java"

# Each rule: (id, file pattern, regex, message). Only flag real risks, must pass on current clean tree.
RULES = [
    ("S1-exec-outside-jail", r"exec/.*\.java", r"Runtime\.getRuntime\(\)\.exec\s*\(",
     "direct Runtime.exec outside ExecJail allowlist"),
    ("S2-processbuilder-outside", r"(?!(exec/)).*\.java", r"new\s+ProcessBuilder\s*\(",
     "ProcessBuilder outside exec/ jail"),
    ("S3-sql-concat", r".*\.java", r"\"SELECT.*\"\s*\+",
     "SQL string concatenation (use ? bind vars)"),
    ("S4-todo-security", r".*\.java", r"//\s*TODO\s*security",
     "TODO security left open"),
    ("S5-printstacktrace-prod", r".*\.java", r"\.printStackTrace\(\)",
     "printStackTrace in prod path (use structured log, no secrets)"),
    ("S6-system-exit", r".*\.java", r"System\.exit\s*\(",
     "System.exit in service code"),
    ("S7-hardcoded-password", r".*\.java", r"(?i)(password|passwd)\s*=\s*\"[^\"]{3,}\"",
     "hardcoded password literal"),
    ("S8-insecure-random", r".*\.java", r"new\s+Random\s*\(",
     "java.util.Random for security decision (use SecureRandom)"),
    ("S9-xxe", r".*\.java", r"DocumentBuilderFactory\.newInstance\(\)",
     "XML parser without XXE hardening"),
]

ALLOW = {
    # file-relative substrings that are reviewed exceptions (document why)
}

def main():
    if not SRC.exists():
        print("sast: src missing")
        sys.exit(1)
    findings = []
    files = list(SRC.rglob("*.java"))
    for rule_id, file_pat, pat, msg in RULES:
        rx_file = re.compile(file_pat)
        rx = re.compile(pat)
        for f in files:
            rel = str(f.relative_to(ROOT))
            # file_pat matches against path inside src/main/java
            inner = str(f.relative_to(SRC))
            # handle negative lookahead patterns: if pattern starts with (?! we match differently
            if file_pat.startswith("(?!(exec/))"):
                if "/exec/" in rel or inner.startswith("exec/"):
                    continue
            else:
                # strip leading dirs: match anywhere
                if not re.search(file_pat, inner):
                    # for broad patterns like .*\.java, always match
                    if file_pat not in (r".*\.java", r"exec/.*\.java"):
                        continue
            try:
                text = f.read_text(encoding="utf-8")
            except Exception:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if rx.search(line):
                    # allow commented justification with NOSAST-REVIEWED?
                    if "NOSAST-REVIEWED" in line:
                        continue
                    findings.append(f"{rel}:{i} [{rule_id}] {msg}: {line.strip()[:160]}")
    if findings:
        print("sast: FAIL findings:")
        for f in findings[:30]:
            print(f"  - {f}")
        sys.exit(1)
    print(f"sast: OK scanned {len(files)} java files, {len(RULES)} rules, no findings (CodeQL runs in CI as primary SAST)")

if __name__ == "__main__":
    main()
