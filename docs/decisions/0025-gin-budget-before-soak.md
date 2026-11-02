# 0025 — A measured budget and a kill-switch for the full-text (GIN) index, before any soak numbers

Status: proposed 2026-11-02 (#79, moved from #65). The budget was written and committed before anything was measured; the measurement and the decision below came after it.

## Context

Facts are indexed per tenant, and the next step for them is full-text search. In Postgres that means a GIN index on the fact values. A GIN index makes every fact insert do extra work. With `fastupdate` on, that work collects in a pending list until a flush, which can pause one unlucky insert. The index also grows on disk and writes more WAL. None of this had been measured for ANVIL, and a soak test run without the index would say nothing about the cost it will carry. Before V10 the schema had no GIN index. `FaultMatrixTest` only creates one on a test database to prove a flush loses no work.

#79 requires that this cost is measured on containerized Postgres against the frozen eval subset, and that a budget with a kill-switch is recorded, before any soak numbers are published. If the budget can't be met, the residual is reported and no soak numbers are claimed.

## The budget

These limits were set before measuring. The candidate is `to_tsvector('simple', value)` on `extracted_facts`. Each limit is measured over one soak day, 10,000 extractions, against the same inserts with no index:

| Limit | Budget |
| --- | --- |
| p95 of one extraction's store transaction | at most 2 ms above the same transaction with no index |
| Slowest single store transaction | at most 100 ms, so a pending-list flush can't stall a request |
| Index size | at most 10 MiB per 10,000 extractions, and no larger than the facts table itself |
| WAL written | at most 2 times the WAL written with no index |

The kill-switch is `DROP INDEX CONCURRENTLY extracted_facts_value_fts_idx;`, run by an operator as `anvil_migrator`. It is safe at any time only if no code path depends on the index. That is part of the decision, and a test checks it.

The verdict is computed by `GinBudget` from the measurement and written to `load/runs/gin-budget/gin-budget.json`. `LoadRig` reads that verdict and refuses to write soak numbers unless it says the budget is met.

## The measurement

`task load:gin` (`GinBudget`) ran on 2026-11-02 at 21:09 UTC against `postgres:18.6` in a container, with default settings, on the same host as the measuring process. Each variant got a fresh database at the latest schema, after a throwaway warm-up run. Each then stored 10,000 extractions over 20 tenants, the way `ExtractionService.store` does: one transaction as `anvil_app` with the tenant set, an `extractions` row, and a row per fact. The facts are the accepted facts of the committed live run over the frozen full-v1 set (record 0024), reused document by document. That's 51,328 facts.

| Variant | Store p50 | p95 | p99 | max | Index | WAL | Peak pending list |
| --- | --- | --- | --- | --- | --- | --- | --- |
| no index | 6.89 ms | 8.89 ms | 10.36 ms | 36.91 ms | none | 36.45 MiB | none |
| GIN, `fastupdate` off | 6.84 ms | 8.07 ms | 9.12 ms | 60.11 ms | 0.52 MiB | 91.32 MiB | none |
| GIN, `fastupdate` on | 7.28 ms | 9.44 ms | 11.09 ms | 25.18 ms | 2.77 MiB | 43.97 MiB | 284 pages |

Against the budget:

| Limit | `fastupdate` off | `fastupdate` on |
| --- | --- | --- |
| p95 above no index (at most 2 ms) | -0.82 ms, pass | +0.55 ms, pass |
| Slowest store (at most 100 ms) | 60.11 ms, pass | 25.18 ms, pass |
| Index per 10,000 extractions (at most 10 MiB) | 0.52 MiB, pass | 2.77 MiB, pass |
| Index to facts table (at most 1) | 0.09, pass | 0.47, pass |
| WAL against no index (at most 2 times) | 2.51 times, **over** | 1.21 times, pass |

The full report and the verdict are in [`load/runs/gin-budget/`](../../load/runs/gin-budget/report.md).

## Decision: add the index with `fastupdate` on

`fastupdate` on meets every limit, so V10 adds `extracted_facts_value_fts_idx` with it, and the soak runs with the index in place (`LoadRig` checks it).

- `fastupdate` off is over budget. It updates the index for every insert, and those small page changes wrote 2.5 times the WAL of no index. The index it builds is smaller, 0.52 MiB against 2.77 MiB, because it never holds a pending list, but WAL is what replication and backups carry.
- `fastupdate` on adds 0.55 ms at p95. Its pending list, sampled every 250 extractions, peaked at 284 pages (about 2.2 MiB), short of the 512 pages the default 4 MiB `gin_pending_list_limit` allows. So no insert in this run had to merge it. Autovacuum merges it, and so does an insert that fills it. That insert is the stall the 100 ms limit is for, and the soak's p99 and max show the case under load.
- The kill-switch is unchanged: `DROP INDEX CONCURRENTLY extracted_facts_value_fts_idx;` as `anvil_migrator`. No code reads the index, and `GinIndexTest` drops it and shows extraction and the facts route still work.

## Consequences

- **The index serves no tenant's lookup yet.** Under RLS, a tenant's one-word lookup at this size is planned as a scan of the tenant's rows through the `(tenant_id, extraction_id, ordinal)` index, filtered by the text match, at a median of 8 ms. The GIN index isn't used, because the tenant predicate RLS adds is more selective. A tenant-scoped search will need its own decision, such as a composite index with the tenant. Until then this index is a measured cost carried for that search, not a speed-up.
- Each soak day adds about 2.8 MiB of index and 20% more WAL.
- The budget holds for one soak day on one host with default settings. A larger table, more facts per document or a tuned server need a new measurement.

## Alternatives considered

- **`fastupdate` off.** Over budget on WAL.
- **No index, and soak without one.** It would leave the next feature's cost unmeasured, which is what #65 and #79 asked to avoid.
- **A composite index with the tenant (`btree_gin`).** That is the tenant-scoped search decision above. It needs an extension and a query that uses it, and neither exists yet.
