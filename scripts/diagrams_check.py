"""Diagrams check: pointer check only (DSL validity in editor, not CI)."""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DSL = ROOT / "docs" / "architecture" / "structurizr" / "workspace.dsl"

if not DSL.exists():
    print(f"diagrams-check: {DSL} missing")
    sys.exit(1)

# Pointer check only: c1/c2/c3 must reference workspace.dsl; views must exist as pointers.
# DSL validity (brace balance, full parse) is owned by VSCode extension + Lite preview.
text = DSL.read_text()
for view in ("C1-SystemContext", "C2-Containers"):
    if view not in text:
        print(f"diagrams-check: pointer {view!r} not found in workspace.dsl")
        sys.exit(1)

for rel, path in (("c1.md", "docs/architecture/c1.md"),
                  ("c2.md", "docs/architecture/c2.md"),
                  ("c3.md", "docs/architecture/slices/o3/c3.md")):
    target = ROOT / path
    if not target.exists():
        print(f"diagrams-check: {target} missing")
        sys.exit(1)
    body = target.read_text()
    if "workspace.dsl" not in body:
        print(f"diagrams-check: {path} does not reference workspace.dsl")
        sys.exit(1)

print("diagrams-check: OK pointers c1/c2/c3 -> workspace.dsl (C1-SystemContext + C2-Containers)")
