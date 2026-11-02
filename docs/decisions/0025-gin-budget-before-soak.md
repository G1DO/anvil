# 0025 — A measured budget and a kill-switch for the full-text (GIN) index, before any soak numbers

Status: proposed 2026-11-02 (#79, moved from #65). The budget below was written and committed before anything was measured. The measurement and the decision are added once the measurement has run.

## Context

Facts are indexed per tenant, and the next step for them is full-text search. In Postgres that means a GIN index on the fact values. A GIN index makes every fact insert do extra work. With `fastupdate` on, that work collects in a pending list until a flush, which can pause one unlucky insert. The index also grows on disk and writes more WAL. None of this has been measured for ANVIL, and a soak test run without the index would say nothing about the cost it will carry. The schema has no GIN index today. `FaultMatrixTest` only creates one on a test database to prove a flush loses no work.

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
