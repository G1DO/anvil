# 0003 — Crash-safe content store with safe garbage collection

Status: accepted.

## Context

Artifact bytes live on the local filesystem while their advertisement lives in `artifacts` rows. A crash during write, rename, or collection can leave a partial file, a file without a row, or a row without a file. Serving a partial file or deleting referenced bytes would be silent data loss.

## Decision

Order durability before advertisement and collect conservatively (`src/main/java/com/g1do/anvil/cas/ContentStore.java`):

- Identity `sha256(bytes)` (lowercase hex), per-tenant paths `tenants/{tenant}/{sha}`, no cross-tenant deduplication.
- Mandatory order: tmp-write in the destination directory → fsync file → atomic rename → fsync directory, then the `artifacts` row. The final name appears only via rename; every read re-verifies the hash and removes (never serves) a torn tail.
- GC deletes only `refcount = 0` rows past `GC_grace=1h` with a per-row `FOR UPDATE` re-check, file-before-row so a crash leaves at worst a row without a file; filesystem sweep reclaims only old files without rows or old tmp files. Any ambiguity retains.

## Consequences

- Readers never observe partial bytes; kills during tmp-write, rename-before-row, and GC file-before-row restart to hash-verified reads with zero torn bytes (proven by `ContentStoreTest`).
- Live references are never collected; orphans are reclaimed after grace.
- Rejected: in-place writes to the final name and eager deletion on ambiguity.
