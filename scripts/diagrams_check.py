"""Diagrams check: Structurizr workspace parses and C1/C2 views stay pinned."""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DSL = ROOT / "docs" / "architecture" / "structurizr" / "workspace.dsl"

if not DSL.exists():
    print(f"diagrams-check: {DSL} missing")
    sys.exit(1)

text = DSL.read_text()
if text.count("{") != text.count("}"):
    print("diagrams-check: unbalanced braces")
    sys.exit(1)

for token in ("model {", "views {", "systemContext anvil",
              "C1-SystemContext", "container anvil",
              "C2-Containers", "styles {"):
    if token not in text:
        print(f"diagrams-check: missing {token!r}")
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

print("diagrams-check: OK workspace.dsl views C1-SystemContext + C2-Containers, c1/c2/c3 linked")
