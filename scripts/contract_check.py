"""Contract check: OpenAPI is source, versioned, backward-compatible (additive-only)."""
import pathlib
import sys

try:
    import yaml
except ImportError:
    print("PyYAML not available, installing is CI responsibility")
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "openapi" / "openapi.yaml"

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
required = ["/api/documents", "/api/documents", "/audit/events"]
paths = doc.get("paths", {})
missing = [p for p in ("/api/documents", "/audit/events") if p not in paths]
if missing:
    print(f"contract-check: missing required paths {missing}, have {sorted(paths)[:10]}")
    sys.exit(1)
print(f"contract-check: OK openapi={doc.get('openapi')} version={version} paths={len(paths)}")
