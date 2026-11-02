# Load run: steady

Offered: 140 extraction requests per second for 5 minutes. Each request submits a document and extracts it.
Run 2026-11-02T21:11:27Z to 2026-11-02T21:16:30Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 42001 | 41670 | 0 | 331 | 0.79% | 1282 ms | 2665 ms | 3044 ms | 3935 ms | 1387 ms |

- Errors by kind: 0 timeouts (30 s), 0 5xx, 0 4xx. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 41670. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 41670 | 0 (0) | 48 KiB | 26.1 MiB | 641 KiB | 0 | 0 | 5 | 2.2% |
| document_versions | 41670 | 0 (0) | 40 KiB | 27.3 MiB | 669 KiB | 0 | 0 | 5 | 2.2% |
| documents | 41670 | 0 (0) | 32 KiB | 10.7 MiB | 263 KiB | 0 | 0 | 5 | 2.0% |
| extracted_facts | 118941 | 0 (0) | 40 KiB | 27.8 MiB | 681 KiB | 0 | 0 | 5 | 1.3% |
| extraction_budgets | 0 | 41670 (41650) | 64 KiB | 72 KiB | 0 KiB | 135 | 0 | 6 | 90.1% |
| extractions | 41670 | 0 (0) | 40 KiB | 31.2 MiB | 767 KiB | 0 | 0 | 5 | 1.7% |
| jobs | 41670 | 0 (0) | 64 KiB | 23.4 MiB | 574 KiB | 0 | 0 | 5 | 2.3% |
| outbox | 41670 | 0 (0) | 24 KiB | 23.9 MiB | 587 KiB | 0 | 0 | 5 | 4.0% |
