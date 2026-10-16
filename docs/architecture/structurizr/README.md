# Structurizr workspace

Canonical C1/C2 diagram source: `workspace.dsl` in this directory.

## Views

- `C1-SystemContext`: tenants, operator, ANVIL, PostgreSQL 18, CAS store, exec workspaces.
- `C2-Containers`: API service, workers, exec jail, and their stores.

## Render

```bash
docker run -it --rm -p 8080:8080 -v "$PWD:/usr/local/structurizr" structurizr/lite
```

Then open Structurizr Lite and load `workspace.dsl`.
