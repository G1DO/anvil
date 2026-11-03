# Full-text (GIN) index budget

Measured 2026-11-03T00:23:10Z on postgres:18.6 (server 18.6 (Debian 18.6-1.pgdg13+2)), default settings, a fresh server per variant.
Workload: 10000 extractions over 20 tenants; facts from the accepted facts of 660 quality documents of eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl, reused in order.
Each variant ran on its own fresh server at the latest schema, after a throwaway warm-up run. At the end of each, the pending list was merged and the table vacuumed before WAL and sizes were read. Budget: record 0025, set before measuring.

| Variant | Store p50 | p95 | p99 | max | Facts table | Index | WAL | Max pending pages | Pending merge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| none | 8.52 ms | 12.46 ms | 15.39 ms | 87.02 ms | 5.84 MiB | 0.00 MiB | 33.79 MiB | - | - |
| gin (fastupdate = off) | 8.78 ms | 12.85 ms | 16.27 ms | 139.48 ms | 5.84 MiB | 0.52 MiB | 84.81 MiB | 0 | - |
| gin (fastupdate = on) | 8.87 ms | 12.23 ms | 15.44 ms | 141.65 ms | 5.84 MiB | 2.34 MiB | 44.26 MiB | 241 | 24 pages in 10.03 ms; a full list of 512 pages, about 213.97 ms |

## gin (fastupdate = off): over budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | 0.39 | at most 2.0 | pass |
| max_store_ms | 139.48 | at most 100.0 | FAIL |
| index_mib_per_10k | 0.52 | at most 10.0 | pass |
| index_to_heap | 0.09 | at most 1.0 | pass |
| wal_ratio | 2.51 | at most 2.0 | FAIL |

A tenant's lookup of one word: median 9.65 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=104.07..1552.92 rows=10 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..104.07 rows=2620 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

## gin (fastupdate = on): over budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | -0.23 | at most 2.0 | pass |
| max_store_ms | 213.97 | at most 100.0 | FAIL |
| index_mib_per_10k | 2.34 | at most 10.0 | pass |
| index_to_heap | 0.4 | at most 1.0 | pass |
| wal_ratio | 1.31 | at most 2.0 | pass |

A tenant's lookup of one word: median 10.39 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=107.99..1554.17 rows=9 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..107.99 rows=2610 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

Verdict: no variant meets the budget. The residual is above; no soak numbers are claimed.
