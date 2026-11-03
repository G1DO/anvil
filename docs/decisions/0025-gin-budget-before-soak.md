# 0025 — A measured budget and a kill-switch for the full-text (GIN) index, before any soak numbers

Status: proposed 2026-11-02, revised 2026-11-03 after review (#79, moved from #65). The budget was written and committed before anything was measured, and hasn't changed since. The measurements and the decision below came after it.

## Context

Facts are indexed per tenant, and the next step for them is full-text search. In Postgres that means a GIN index on the fact values. A GIN index makes every fact insert do extra work. With `fastupdate` on, that work collects in a pending list until a flush, which can pause one unlucky insert. The index also grows on disk and writes more WAL. None of this had been measured for ANVIL, and a soak test run without the index would say nothing about the cost it will carry. The schema has no GIN index. `FaultMatrixTest` only creates one on a test database to prove a flush loses no work.

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

The verdict is computed by `GinBudget` from the measurement and written to `load/runs/gin-budget/gin-budget.json`. `LoadRig` reads that verdict and refuses to write soak numbers unless it measured a full soak day and says the budget is met.

## The measurements

`task load:gin` (`GinBudget`) stores one soak day, 10,000 extractions over 20 tenants, the way `ExtractionService.store` does: one transaction as `anvil_app` with the tenant set, an `extractions` row, and a row per fact. The facts are the accepted facts of the committed live run over the frozen full-v1 set (record 0024), reused document by document. That's 51,328 facts. It runs once with no index, once with the index and `fastupdate` off, and once with it on, on `postgres:18.6` in a container with default settings.

It was measured three times. Review of the first measurement showed it undercounted.

1. **Shared server, deferred work left out (superseded).** All variants ran in one server, and WAL and sizes were read with the pending list still unmerged. Review found two problems. WAL is counted across the whole server, so autovacuum of the earlier variants' databases landed in the later variants' WAL. And with `fastupdate` on, the merge of the pending list into the index, the bulk of its work, was never counted. This run found `fastupdate` on within budget and V10 added the index. Both are withdrawn. The numbers stay in this record's history.
2. **A fresh server per variant, deferred work counted, cold servers ([`gin-budget-cold-servers`](../../load/runs/gin-budget-cold-servers/report.md)).** Each variant got its own server, and each ended with the pending list merged and the table vacuumed before WAL and sizes were read. Both variants were over budget, but the servers started cold, so the slowest store rose for every variant, including no index (87 ms). The merge of a full pending list was also extrapolated from a merge of 24 pages.
3. **Warm servers, the full-list merge measured directly ([`gin-budget`](../../load/runs/gin-budget/report.md), 2026-11-03 00:37 UTC). This is the verdict.** Each server is warmed with 2,000 extractions, which are vacuumed before a checkpoint, and sizes are the day's growth. With `fastupdate` on, stores then go on one at a time, with autovacuum paused on the table, until one fills the pending list and merges it itself. That store's time is what the slowest-store limit is about.

| Variant (measurement 3) | Store p50 | p95 | p99 | max | Index growth | WAL | Store that merged a full list |
| --- | --- | --- | --- | --- | --- | --- | --- |
| no index | 8.85 ms | 12.38 ms | 17.42 ms | 72.87 ms | none | 36.38 MiB | none |
| GIN, `fastupdate` off | 8.90 ms | 12.39 ms | 17.59 ms | 59.07 ms | 0.38 MiB | 98.82 MiB | none |
| GIN, `fastupdate` on | 9.46 ms | 17.62 ms | 23.36 ms | 56.03 ms | 1.53 MiB | 45.19 MiB | 110.77 ms (514 pages, after 14,195 stores) |

Against the budget:

| Limit | `fastupdate` off | `fastupdate` on |
| --- | --- | --- |
| p95 above no index (at most 2 ms) | +0.01 ms, pass | +5.24 ms, **over** |
| Slowest store (at most 100 ms) | 59.07 ms, pass | 110.77 ms, the store that merged a full list, **over** |
| Index per 10,000 extractions (at most 10 MiB) | 0.38 MiB, pass | 1.53 MiB, pass |
| Index to facts table (at most 1) | 0.06, pass | 0.26, pass |
| WAL against no index (at most 2 times) | 2.72 times, **over** | 1.24 times, pass |

What holds across the measurements:
- **`fastupdate` off writes too much WAL.** All three measurements put it over: 2.51, 2.51 and 2.72 times. It updates the index for every insert, and those small page changes are expensive to log.
- **`fastupdate` on stalls one store each time the list fills.** In a 500-extraction trial run of the harness (not committed), the store that merged a full list took 108 ms; in measurement 3 it took 110.77 ms. During measurement 3's day the list peaked at 218 pages, so autovacuum merged it before any store had to. With 4 MiB of pending entries, that's one stall about every 14,000 stores if autovacuum doesn't merge the list first. At a soak day's rate, autovacuum usually would, which is why a day can pass without one. The budget is about the stall when it happens, not how often.
- **p95 overhead is noisy at this size.** Across the measurements it was +0.55, −0.23 and +5.24 ms for `fastupdate` on, against a limit of 2 ms. The verdict doesn't rest on it: the stall alone is over budget.

## Decision: over budget, so no index and no soak numbers

Neither variant meets the budget. As record 0025 and #79 set out before measuring, the residual is reported and no soak numbers are claimed:
- The schema has no full-text index. V10, added after measurement 1, is removed.
- `LoadRig` refuses to run the soak against this verdict. The soak run made under measurement 1's verdict is withdrawn: it stays in git history, and nothing cites it.
- The residual: `fastupdate` off is 0.72 times the no-index WAL over its limit. `fastupdate` on is 10.77 ms over the slowest-store limit, and, in this run, 3.24 ms over the p95 limit.
- The kill-switch stays recorded for when an index is added: `DROP INDEX CONCURRENTLY extracted_facts_value_fts_idx;` as `anvil_migrator`. No code reads the index, and `GinIndexTest` builds each measured variant with the statement the budget measured, then drops it as the operator role and shows extraction and the facts route still work.
- The steady and flood load runs are not soak numbers. They run on the schema as it is, without the index, and are published in `load/README.md`.

## Consequences

- **The index would serve no tenant's lookup yet anyway.** Under RLS, a tenant's one-word lookup at this size is planned as a scan of the tenant's rows through the `(tenant_id, extraction_id, ordinal)` index, filtered by the text match, at a median of 11 to 16 ms. The GIN index isn't used, because the tenant predicate RLS adds is more selective. A tenant-scoped search needs its own decision.
- **Full-text search needs a variant this budget hasn't measured.** For example, `fastupdate` on with a smaller per-index `gin_pending_list_limit`, so each merge is shorter, or a composite index with the tenant (`btree_gin`). Measuring one is a new run of `task load:gin` against this same budget. The budget itself stays as written.
- **No soak numbers exist for #79.** Its soak acceptance check is met the way it allows: the residual is reported and no soak claim is made.
- The budget holds for one soak day on one host with default settings. A larger table, more facts per document or a tuned server need a new measurement.

## Alternatives considered

- **Keep measurement 1's verdict.** It left out the merge and counted other databases' WAL, so it can't stand.
- **Adjust the budget to fit the result.** The point of setting it first was that it doesn't move after a measurement.
- **Add the index anyway, since it passes most limits.** Each variant fails a limit, and no query uses the index.
- **Soak without the index.** The soak would leave the next feature's cost unmeasured, which is what #65 and #79 asked to avoid, and #79 says no soak numbers are claimed when the budget isn't met.
