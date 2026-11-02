# Load run: flood

Offered: a 10x flood: 1,400 extraction requests per second for 60 seconds, then 14 per second for 60 seconds to show recovery. Each request submits a document and extracts it.
Run 2026-11-02T22:33:22Z to 2026-11-02T22:36:06Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 84840 | 9005 | 0 | 75835 | 89.39% | 14267 ms | 18617 ms | 19635 ms | 21087 ms | 17189 ms |

- Flood: 8165 requests, 0 errors; extract p50 15018 ms, p95 18735 ms.
- Recovery: 840 requests, 0 errors; extract p50 1176 ms, p95 1545 ms.
- Errors by kind: 0 timeouts (30 s), 0 5xx, 0 4xx. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 9005. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 9005 | 0 (0) | 48 KiB | 5.8 MiB | 652 KiB | 0 | 0 | 2 | 3.5% |
| document_versions | 9005 | 0 (0) | 40 KiB | 6.0 MiB | 678 KiB | 0 | 0 | 2 | 2.8% |
| documents | 9005 | 0 (0) | 32 KiB | 2.3 MiB | 262 KiB | 0 | 0 | 2 | 6.9% |
| extracted_facts | 25701 | 0 (0) | 40 KiB | 7.3 MiB | 822 KiB | 0 | 0 | 2 | 2.9% |
| extraction_budgets | 0 | 9005 (9005) | 64 KiB | 64 KiB | 0 KiB | 21 | 13 | 3 | 77.9% |
| extractions | 9005 | 0 (0) | 40 KiB | 6.9 MiB | 779 KiB | 0 | 0 | 1 | 4.4% |
| jobs | 9005 | 0 (0) | 64 KiB | 4.9 MiB | 554 KiB | 0 | 0 | 2 | 3.8% |
| outbox | 9005 | 0 (0) | 24 KiB | 5.2 MiB | 585 KiB | 0 | 0 | 2 | 4.5% |
