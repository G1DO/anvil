# Load run: steady

Offered: 140 extraction requests per second for 5 minutes. Each request submits a document and extracts it.
Run 2026-11-03T00:39:14Z to 2026-11-03T00:44:27Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 42001 | 34078 | 0 | 7923 | 18.86% | 6905 ms | 9229 ms | 11470 ms | 13135 ms | 7771 ms |

- Errors by kind: 0 timeouts (30 s), 0 connection errors (refused or reset), 0 5xx, 0 4xx, 0 unreadable replies. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 34078. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 completed | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 34078 | 0 (0) | 48 KiB | 21.0 MiB | 629 KiB | 0 | 0 | 5 | 2.3% |
| document_versions | 34078 | 0 (0) | 40 KiB | 22.4 MiB | 672 KiB | 0 | 0 | 5 | 2.3% |
| documents | 34078 | 0 (0) | 32 KiB | 8.9 MiB | 266 KiB | 0 | 0 | 5 | 1.6% |
| extracted_facts | 97279 | 0 (0) | 24 KiB | 21.1 MiB | 633 KiB | 0 | 0 | 6 | 0.8% |
| extraction_budgets | 0 | 34078 (34065) | 64 KiB | 72 KiB | 0 KiB | 180 | 0 | 6 | 88.0% |
| extractions | 34078 | 0 (0) | 40 KiB | 25.7 MiB | 770 KiB | 0 | 0 | 5 | 1.7% |
| jobs | 34078 | 0 (0) | 64 KiB | 19.3 MiB | 577 KiB | 0 | 0 | 5 | 2.1% |
| outbox | 34078 | 0 (0) | 24 KiB | 19.1 MiB | 574 KiB | 0 | 0 | 5 | 4.0% |
