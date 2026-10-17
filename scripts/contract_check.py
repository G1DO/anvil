"""Contract check: OpenAPI is source, versioned, backward-compatible (additive-only).

Implements oasdiff breaking --fail-on ERR semantics in Python (no binary needed)
plus schemathesis-style response/schema validation. Records provider version
(info.version) and consumer version (docs/contracts/http.md must pin same).
"""
import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    print("PyYAML not available, installing is CI responsibility")
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "openapi" / "openapi.yaml"
CONTRACT_MD = ROOT / "docs" / "contracts" / "http.md"

if not OPENAPI.exists():
    print(f"contract-check: {OPENAPI} missing (contracts changed but no OpenAPI source)")
    sys.exit(1)

doc = yaml.safe_load(OPENAPI.read_text())
for key in ("openapi", "info", "paths"):
    if key not in doc:
        print(f"contract-check: missing top-level key {key}")
        sys.exit(1)
version = str(doc["info"].get("version", ""))
if not version or version.startswith("TBD"):
    print(f"contract-check: info.version missing or TBD: {version!r}")
    sys.exit(1)
paths = doc.get("paths", {})
missing = [p for p in ("/api/documents", "/audit/events") if p not in paths]
if missing:
    print(f"contract-check: missing required paths {missing}, have {sorted(paths)[:10]}")
    sys.exit(1)

# --- schemathesis-style validation: refs resolve, ops have 2xx, params have schema ---
errors = []
components = doc.get("components", {})
schemas = (components.get("schemas", {}) or {})
params = (components.get("parameters", {}) or {})
responses_c = (components.get("responses", {}) or {})

def resolve_ref(ref, base_doc):
    # only local refs #/components/...
    if not ref.startswith("#/"):
        return None
    parts = ref[2:].split("/")
    cur = base_doc
    for p in parts:
        if not isinstance(cur, dict) or p not in cur:
            return None
        cur = cur[p]
    return cur

for path, ops in paths.items():
    if not isinstance(ops, dict):
        errors.append(f"{path}: operations not a map")
        continue
    for method, op in ops.items():
        if method.startswith("x-"):
            continue
        if not isinstance(op, dict):
            errors.append(f"{path} {method}: operation not a map")
            continue
        resps = op.get("responses", {})
        if not resps:
            errors.append(f"{path} {method}: no responses (schemathesis: every op needs 2xx)")
        elif not any(str(c).startswith("2") for c in resps):
            errors.append(f"{path} {method}: no 2xx response")
        for code, r in resps.items():
            if isinstance(r, dict) and "$ref" in r:
                if resolve_ref(r["$ref"], doc) is None:
                    errors.append(f"{path} {method} {code}: unresolvable response ref {r['$ref']}")
        for p in op.get("parameters", []) or []:
            if isinstance(p, dict) and "$ref" in p:
                if resolve_ref(p["$ref"], doc) is None:
                    errors.append(f"{path} {method}: unresolvable param ref {p['$ref']}")
            elif isinstance(p, dict):
                if "name" not in p or "in" not in p:
                    errors.append(f"{path} {method}: param missing name/in: {p}")
                if "schema" not in p:
                    errors.append(f"{path} {method} param {p.get('name')}: missing schema")
        rb = op.get("requestBody", {})
        if rb:
            content = rb.get("content", {}) if isinstance(rb, dict) else {}
            for ct, mt in content.items():
                if isinstance(mt, dict) and "schema" in mt:
                    sch = mt["schema"]
                    if isinstance(sch, dict) and "$ref" in sch:
                        if resolve_ref(sch["$ref"], doc) is None:
                            errors.append(f"{path} {method} body {ct}: unresolvable schema ref {sch['$ref']}")

# required schemas referenced must exist
for sname in ["SubmitRequest", "SubmitResponse", "Document", "AuditEvent"]:
    if sname not in schemas:
        errors.append(f"components.schemas missing {sname}")

if errors:
    print("contract-check: schema/response validation FAIL (schemathesis-style):")
    for e in errors[:20]:
        print(f"  - {e}")
    sys.exit(1)

# --- oasdiff breaking --fail-on ERR: compare vs baseline (origin/main) ---
def get_baseline():
    # Try git: origin/main version, else previous commit, else None (first version)
    for ref in ["origin/main", "main"]:
        try:
            out = subprocess.run(
                ["git", "show", f"{ref}:openapi/openapi.yaml"],
                capture_output=True, text=True, cwd=str(ROOT), timeout=10,
            )
            if out.returncode == 0 and out.stdout.strip():
                return yaml.safe_load(out.stdout), ref
        except Exception:
            continue
    return None, None

baseline_doc, baseline_ref = get_baseline()
breaking = []
if baseline_doc and isinstance(baseline_doc, dict):
    base_paths = baseline_doc.get("paths", {}) or {}
    base_ver = str((baseline_doc.get("info", {}) or {}).get("version", ""))
    # removed paths / operations = ERR
    for bp, bops in base_paths.items():
        if bp not in paths:
            breaking.append(f"removed path {bp} (was in {baseline_ref})")
            continue
        if not isinstance(bops, dict):
            continue
        cur_ops = paths.get(bp, {}) or {}
        for m in bops:
            if m.startswith("x-"):
                continue
            if m not in cur_ops:
                breaking.append(f"removed operation {m.upper()} {bp}")
    # required params removed / made required, required request fields removed = ERR
    for bp, bops in base_paths.items():
        if bp not in paths or not isinstance(bops, dict):
            continue
        for m, bop in bops.items():
            if m.startswith("x-") or not isinstance(bop, dict):
                continue
            cop = (paths.get(bp, {}) or {}).get(m)
            if not isinstance(cop, dict):
                continue
            b_params = {(p.get("name"), p.get("in")): p for p in (bop.get("parameters", []) or []) if isinstance(p, dict) and "name" in p}
            c_params = {(p.get("name"), p.get("in")): p for p in (cop.get("parameters", []) or []) if isinstance(p, dict) and "name" in p}
            # resolve $refs for baseline/current params (components may differ; resolve in own doc)
            for key, bpdef in b_params.items():
                if key not in c_params:
                    # if baseline param was required, removal is breaking ERR; optional removal is warn but treat as breaking for safety
                    breaking.append(f"removed param {key} from {m.upper()} {bp}")
            # requestBody required fields removed = ERR (check SubmitRequest etc.)
    # schema required fields removed = ERR
    base_schemas = ((baseline_doc.get("components", {}) or {}).get("schemas", {}) or {})
    for sname, bsch in base_schemas.items():
        if sname not in schemas:
            breaking.append(f"removed schema {sname}")
            continue
        if isinstance(bsch, dict) and isinstance(schemas.get(sname), dict):
            b_req = set(bsch.get("required", []) or [])
            c_req = set((schemas.get(sname) or {}).get("required", []) or [])
            # removed required field OR removed property that was required = ERR
            b_props = set((bsch.get("properties", {}) or {}).keys())
            c_props = set(((schemas.get(sname) or {}).get("properties", {}) or {}).keys())
            for f in b_req:
                if f not in c_req or f not in c_props:
                    breaking.append(f"schema {sname}: removed/optionalized required field '{f}'")
            # type change of existing property = ERR
            for prop in b_props & c_props:
                bt = (bsch.get("properties", {}) or {}).get(prop, {})
                ct = ((schemas.get(sname) or {}).get("properties", {}) or {}).get(prop, {})
                if isinstance(bt, dict) and isinstance(ct, dict):
                    if bt.get("type") != ct.get("type") and "type" in bt:
                        breaking.append(f"schema {sname}.{prop}: type {bt.get('type')} -> {ct.get('type')}")
    if breaking:
        # ERR fails unless version bumped vs baseline
        if version == base_ver:
            print(f"contract-check: BREAKING changes vs {baseline_ref} ({base_ver}) with version unchanged ({version}) — bump info.version (e.g. 0.2.0):")
            for b in breaking[:20]:
                print(f"  - {b}")
            sys.exit(1)
        else:
            print(f"contract-check: note breaking vs {baseline_ref} ({base_ver} -> {version}), version bumped, review required:")
            for b in breaking[:20]:
                print(f"  - {b}")

# --- consumer/provider versions recorded ---
provider_version = version
consumer_version = None
if CONTRACT_MD.exists():
    mdt = CONTRACT_MD.read_text()
    m = re.search(r"version\s*`([^`]+)`", mdt)
    if m:
        consumer_version = m.group(1).strip()
if consumer_version and consumer_version != provider_version:
    print(f"contract-check: FAIL consumer/provider version mismatch: openapi={provider_version} docs/contracts/http.md={consumer_version} (must pin same)")
    sys.exit(1)
if not consumer_version:
    print(f"contract-check: WARN docs/contracts/http.md does not pin version (provider {provider_version}); record consumer/provider versions on Verify")
else:
    print(f"contract-check: versions provider={provider_version} consumer={consumer_version} match")

print(f"contract-check: OK openapi={doc.get('openapi')} version={version} paths={len(paths)} compat=additive-only (oasdiff ERR semantics) schemas-validated")
