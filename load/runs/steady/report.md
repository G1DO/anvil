# Load run: steady

Offered: 140 extraction requests per second for 5 minutes. Each request submits a document and extracts it.
Run 2026-11-02T22:25:57Z to 2026-11-02T22:31:11Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 42001 | 30563 | 0 | 11438 | 27.23% | 7583 ms | 11508 ms | 14387 ms | 17981 ms | 9762 ms |

- Errors by kind: 0 timeouts (30 s), 0 5xx, 0 4xx. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 30563. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 30563 | 0 (0) | 48 KiB | 18.8 MiB | 630 KiB | 0 | 0 | 5 | 2.4% |
| document_versions | 30563 | 0 (0) | 40 KiB | 20.2 MiB | 674 KiB | 0 | 0 | 5 | 2.3% |
| documents | 30563 | 0 (0) | 32 KiB | 8.0 MiB | 267 KiB | 0 | 0 | 5 | 1.9% |
| extracted_facts | 87244 | 0 (0) | 40 KiB | 20.5 MiB | 684 KiB | 0 | 0 | 6 | 0.8% |
| extraction_budgets | 0 | 30563 (30563) | 64 KiB | 64 KiB | 0 KiB | 0 | 0 | 5 | 78.9% |
| extractions | 30563 | 0 (0) | 40 KiB | 22.9 MiB | 766 KiB | 0 | 0 | 5 | 1.8% |
| jobs | 30563 | 0 (0) | 64 KiB | 17.4 MiB | 580 KiB | 0 | 0 | 5 | 2.1% |
| outbox | 30563 | 0 (0) | 24 KiB | 17.3 MiB | 580 KiB | 0 | 0 | 5 | 3.9% |
