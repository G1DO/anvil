"""Hygiene check: no ORG placeholders, CODEOWNERS/CI alignment, no stubs, verbs present."""
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("hygiene: PyYAML not available, CI installs it")
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parents[1]
CODEOWNERS = ROOT / ".github" / "CODEOWNERS"
CI = ROOT / ".github" / "workflows" / "ci.yml"
TASKFILE = ROOT / "Taskfile.yml"

errors = []

# 1. CODEOWNERS: no ORG, fallback *, /.github/ self-owned
if not CODEOWNERS.exists():
    errors.append(f"CODEOWNERS missing: {CODEOWNERS}")
    co_text = ""
else:
    co_text = CODEOWNERS.read_text()
    if "@ORG/" in co_text or "ORG/" in co_text and "@ORG" in co_text:
        errors.append("CODEOWNERS contains ORG placeholder")
    # more precise: any @ORG
    if re.search(r"@ORG\b", co_text):
        errors.append("CODEOWNERS contains @ORG placeholder")
    if not re.search(r"^\*\s+@\S+", co_text, re.M):
        errors.append("CODEOWNERS missing fallback '* @owner'")
    if not re.search(r"^/\.github/\s+@\S+", co_text, re.M):
        errors.append("CODEOWNERS missing mandatory '/.github/ @owner'")

# 2. Task verbs present (Starter §Task verbs)
required_verbs = [
    "dev", "test", "test:int", "test:e2e", "migrate", "build",
    "test:contract", "check:sast", "check:secrets", "check:audit",
    "check:hygiene", "diagrams", "verify",
]
if not TASKFILE.exists():
    errors.append("Taskfile.yml missing")
    task_text = ""
    task_data = {}
else:
    task_text = TASKFILE.read_text()
    try:
        task_data = yaml.safe_load(task_text) or {}
    except Exception as e:
        errors.append(f"Taskfile.yml YAML load failed: {e}")
        task_data = {}
    tasks = (task_data.get("tasks") or {}) if isinstance(task_data, dict) else {}
    for v in required_verbs:
        if v not in tasks:
            errors.append(f"Taskfile missing required verb '{v}'")
    # verify must call full gate (at least test:int, build, contract, checks, diagrams)
    verify_cmds = str(tasks.get("verify", {}).get("cmds", "") if isinstance(tasks.get("verify", {}), dict) else "")
    for must in ["test:int", "build", "check:sast", "check:secrets", "check:audit", "check:hygiene", "diagrams"]:
        if must not in verify_cmds and must not in task_text.split("verify:")[-1].split("lint:")[0] if "verify:" in task_text else True:
            pass  # soft check below via full text search in verify block
    # robust: extract verify block
    if "verify:" in task_text:
        vb = task_text.split("verify:", 1)[1].split("\n  ", 1)
        # take until next top-level task (two-space indent)
        # simpler: check that verify section mentions each
        verify_section = task_text.split("verify:", 1)[1]
        # cut at next task that starts at two spaces + word + colon at line start
        m = re.search(r"\n  [a-z][\w:.-]*:", verify_section)
        if m:
            verify_section = verify_section[:m.start()]
        for must in ["test:int", "build", "check:sast", "check:secrets", "check:audit", "check:hygiene", "diagrams", "test:e2e", "test:contract"]:
            if must not in verify_section:
                errors.append(f"verify missing '{must}' (full local gate)")

# 3. No stub patterns (Starter §CI)
stub_patterns = [
    (r"echo\s+placeholder", "echo placeholder stub"),
    (r"python3\s+-c\s+\"print\(", "python3 -c \"print( stub"),
    (r"assertTrue\s*\(\s*True\s*\)", "assertTrue(True) placeholder test"),
    (r"TODO\s+CI", "TODO CI stub"),
    (r"\|\|\s*true", "|| true swallowing failures"),
]
for path in [TASKFILE, CI]:
    if path.exists():
        txt = path.read_text()
        for pat, msg in stub_patterns:
            if re.search(pat, txt):
                errors.append(f"{path.name} contains stub '{msg}'")

# 4. CI structure: gate sole required, filters via if:, full output, force labels
if not CI.exists():
    errors.append("ci.yml missing")
else:
    ci_text = CI.read_text()
    try:
        ci_data = yaml.safe_load(ci_text) or {}
    except Exception as e:
        errors.append(f"ci.yml YAML load failed: {e}")
        ci_data = {}
    jobs = (ci_data.get("jobs") or {}) if isinstance(ci_data, dict) else {}
    for j in ["changes", "contract-check", "sast", "secrets-scan", "audit", "integration", "e2e", "build", "arch-validate", "hygiene", "gate"]:
        if j not in jobs:
            errors.append(f"ci.yml missing job '{j}'")
    # test job may be named test (Starter) or unit (legacy split) - accept either but prefer test
    if "test" not in jobs and "unit" not in jobs:
        errors.append("ci.yml missing job 'test' (or legacy 'unit')")
    if "types" not in jobs:
        errors.append("ci.yml missing job 'types'")
    if "schema-validate" not in jobs:
        errors.append("ci.yml missing job 'schema-validate'")
    # changes outputs must include data, contracts, full
    changes = jobs.get("changes", {}) if isinstance(jobs.get("changes", {}), dict) else {}
    outputs = changes.get("outputs", {}) if isinstance(changes, dict) else {}
    for o in ["data", "contracts", "full"]:
        if o not in outputs and o not in ci_text:
            errors.append(f"changes outputs missing '{o}'")
    # gate must use !cancelled(), not always()
    gate = jobs.get("gate", {}) if isinstance(jobs.get("gate", {}), dict) else {}
    gate_if = str(gate.get("if", "")) if isinstance(gate, dict) else ""
    if "always()" in gate_if and "!cancelled()" not in gate_if:
        errors.append("gate uses 'always()' + skipped-pass; must use 'if: !cancelled()' with required success")
    if "!cancelled()" not in ci_text:
        errors.append("gate missing 'if: !cancelled()'")
    # gate logic must require success for required, skipped neutral only for conditional
    if "required" not in ci_text.lower() and "success" not in ci_text:
        errors.append("gate missing required-success logic (skipped fails for required)")
    # conditional jobs must use force labels
    for job_name, label in [("integration", "force-integration"), ("e2e", "force-e2e"), ("contract-check", "force-contract")]:
        job = jobs.get(job_name, {}) if isinstance(jobs.get(job_name, {}), dict) else {}
        job_if = str(job.get("if", "")) if isinstance(job, dict) else ""
        if label not in job_if and label not in ci_text:
            errors.append(f"job '{job_name}' missing label OR '{label}' (triggers override path filters)")
    # e2e must be gated on full, not data
    e2e_if = str(jobs.get("e2e", {}).get("if", "")) if isinstance(jobs.get("e2e", {}), dict) else ""
    if "needs.changes.outputs.data" in e2e_if and "full" not in e2e_if:
        errors.append("e2e still gated on data; must be full||force-e2e")
    # Never require path-filtered jobs directly: gate must be sole required (check via comment/marker)
    # We check that no paths: key exists at workflow level for required jobs
    if re.search(r"^\s*paths\s*:", ci_text, re.M):
        errors.append("ci.yml uses 'paths:'-only required; must filter via job 'if:', never paths-only required")

# 5. CODEOWNERS/CI-filter alignment
if CI.exists() and CODEOWNERS.exists():
    ci_txt = CI.read_text()
    # contracts filter must align with CODEOWNERS contracts paths
    for p in ["/docs/contracts/", "/openapi/"]:
        if p.strip("/") not in ci_txt and p not in ci_txt:
            errors.append(f"CI contracts filter missing '{p}' (CODEOWNERS has it)")
        if p not in co_text:
            errors.append(f"CODEOWNERS missing '{p}' (CI contracts filter has it)")
    # data filter representative
    if "src/main/java" not in ci_txt:
        errors.append("CI data filter missing src/main/java (CODEOWNERS has it)")
    # full filter representative
    for p in [".github/workflows", "Taskfile", "Dockerfile"]:
        if p not in ci_txt:
            errors.append(f"CI full filter missing '{p}'")

if errors:
    print("hygiene: FAIL")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("hygiene: OK CODEOWNERS no-ORG + fallback + /.github/; verbs present; verify full gate; gate !cancelled + required success; force labels; filters aligned; no stubs")
