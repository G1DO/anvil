# Load run: steady

Offered: 140 extraction requests per second for 5 minutes. Each request submits a document and extracts it.
k6 settings changed for this run: {PRE_VUS=300}.
Run 2026-11-03T00:51:24Z to 2026-11-03T00:56:26Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 42001 | 41702 | 0 | 299 | 0.71% | 1233 ms | 2216 ms | 2946 ms | 4445 ms | 964 ms |

- Errors by kind: 0 timeouts (30 s), 0 connection errors (refused or reset), 0 5xx, 0 4xx, 0 unreadable replies. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 41702. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 completed | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 41702 | 0 (0) | 48 KiB | 26.1 MiB | 639 KiB | 0 | 0 | 5 | 2.2% |
| document_versions | 41702 | 0 (0) | 40 KiB | 27.0 MiB | 662 KiB | 0 | 0 | 5 | 2.1% |
| documents | 41702 | 0 (0) | 32 KiB | 10.8 MiB | 264 KiB | 0 | 0 | 5 | 1.7% |
| extracted_facts | 119032 | 0 (0) | 24 KiB | 25.7 MiB | 630 KiB | 0 | 0 | 5 | 0.8% |
| extraction_budgets | 0 | 41702 (41701) | 64 KiB | 72 KiB | 0 KiB | 66 | 0 | 6 | 88.3% |
| extractions | 41702 | 0 (0) | 40 KiB | 31.3 MiB | 767 KiB | 0 | 0 | 5 | 1.5% |
| jobs | 41702 | 0 (0) | 64 KiB | 23.3 MiB | 570 KiB | 0 | 0 | 5 | 2.0% |
| outbox | 41702 | 0 (0) | 24 KiB | 24.0 MiB | 590 KiB | 0 | 0 | 5 | 4.0% |
