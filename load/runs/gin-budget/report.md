# Full-text (GIN) index budget

Measured 2026-11-02T21:09:01Z on postgres:18.6 (server 18.6 (Debian 18.6-1.pgdg13+2)), default settings.
Workload: 10000 extractions over 20 tenants; facts from the accepted facts of 660 quality documents of eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl, reused in order.
Each variant ran on a fresh database at the latest schema, after a throwaway warm-up run. Budget: record 0025, set before measuring.

| Variant | Store p50 | p95 | p99 | max | Facts table | Index | WAL | Max pending pages |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| none | 6.89 ms | 8.89 ms | 10.36 ms | 36.91 ms | 5.84 MiB | 0.00 MiB | 36.45 MiB | - |
| gin (fastupdate = off) | 6.84 ms | 8.07 ms | 9.12 ms | 60.11 ms | 5.85 MiB | 0.52 MiB | 91.32 MiB | 0 |
| gin (fastupdate = on) | 7.28 ms | 9.44 ms | 11.09 ms | 25.18 ms | 5.84 MiB | 2.77 MiB | 43.97 MiB | 284 |

## gin (fastupdate = off): over budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | -0.82 | at most 2.0 | pass |
| max_store_ms | 60.11 | at most 100.0 | pass |
| index_mib_per_10k | 0.52 | at most 10.0 | pass |
| index_to_heap | 0.09 | at most 1.0 | pass |
| wal_ratio | 2.51 | at most 2.0 | FAIL |

A tenant's lookup of one word: median 7.96 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=103.84..1545.39 rows=9 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..103.84 rows=2589 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

## gin (fastupdate = on): within budget

| Check | Measured | Budget | Result |
| --- | --- | --- | --- |
| p95_overhead_ms | 0.55 | at most 2.0 | pass |
| max_store_ms | 25.18 | at most 100.0 | pass |
| index_mib_per_10k | 2.77 | at most 10.0 | pass |
| index_to_heap | 0.47 | at most 1.0 | pass |
| wal_ratio | 1.21 | at most 2.0 | pass |

A tenant's lookup of one word: median 8.53 ms. Plan:

```
Bitmap Heap Scan on extracted_facts  (cost=103.84..1544.67 rows=9 width=16)
Recheck Cond: (tenant_id = current_setting('app.tenant'::text, true))
Filter: (to_tsvector('simple'::regconfig, value) @@ '''halvane'''::tsquery)
->  Bitmap Index Scan on extracted_facts_tenant_id_extraction_id_ordinal_key  (cost=0.00..103.84 rows=2590 width=0)
Index Cond: (tenant_id = current_setting('app.tenant'::text, true))
```

Verdict: within budget with gin_fastupdate_on. Soak numbers may be published with this index in place.
