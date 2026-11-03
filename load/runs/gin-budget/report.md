# Full-text (GIN) index budget

Measured 2026-11-03T00:37:48Z on postgres:18.6 (server 18.6 (Debian 18.6-1.pgdg13+2)), default settings, a fresh server per variant.
Workload: 10000 extractions over 20 tenants; facts from the accepted facts of 660 quality documents of eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl, reused in order.
Each variant ran on its own fresh server at the latest schema, warmed with 2000 extractions that were vacuumed before a checkpoint. At the end of each, the pending list was merged and the table vacuumed before WAL and sizes were read; sizes are the day's growth. With fastupdate on, stores then went on, with autovacuum paused, until one filled the pending list and merged it itself. Budget: record 0025, set before measuring.

| Variant | Store p50 | p95 | p99 | max | Facts table growth | Index growth | WAL | Max pending pages | Store that merged a full list |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| none | 8.85 ms | 12.38 ms | 17.42 ms | 72.87 ms | 5.84 MiB | 0.00 MiB | 36.38 MiB | - | - |
| gin (fastupdate = off) | 8.9 ms | 12.39 ms | 17.59 ms | 59.07 ms | 5.84 MiB | 0.38 MiB | 98.82 MiB | 0 | - |
| gin (fastupdate = on) | 9.46 ms | 17.62 ms | 23.36 ms | 56.03 ms | 5.84 MiB | 1.53 MiB | 45.19 MiB | 218 | 110.77 ms (514 pages, after 14195 stores) |

## gin (fastupdate = off): over budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | 0.01 | at most 2.0 | pass |
| max_store_ms | 59.07 | at most 100.0 | pass |
| index_mib_per_10k | 0.38 | at most 10.0 | pass |
| index_to_heap | 0.06 | at most 1.0 | pass |
| wal_ratio | 2.72 | at most 2.0 | FAIL |

A tenant's lookup of one word: median 15.52 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=128.14..1871.25 rows=11 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..128.14 rows=3163 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

## gin (fastupdate = on): over budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | 5.24 | at most 2.0 | FAIL |
| max_store_ms | 110.77 | at most 100.0 | FAIL |
| index_mib_per_10k | 1.53 | at most 10.0 | pass |
| index_to_heap | 0.26 | at most 1.0 | pass |
| wal_ratio | 1.24 | at most 2.0 | pass |

A tenant's lookup of one word: median 11.46 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=123.32..1837.00 rows=11 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..123.32 rows=3053 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

Verdict: no variant meets the budget. The residual is above; no soak numbers are claimed.
