# Load run: flood

Offered: a 10x flood: 1,400 extraction requests per second for 60 seconds, then 14 per second for 60 seconds to show recovery. Each request submits a document and extracts it.
Run 2026-11-03T00:46:38Z to 2026-11-03T00:49:21Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 84841 | 8411 | 0 | 76430 | 90.09% | 15624 ms | 19248 ms | 19597 ms | 20240 ms | 17212 ms |

- Flood: 7571 requests, 0 errors; extract p50 15915 ms, p95 19280 ms.
- Recovery: 840 requests, 0 errors; extract p50 1172 ms, p95 1520 ms.
- Errors by kind: 0 timeouts (30 s), 0 connection errors (refused or reset), 0 5xx, 0 4xx, 0 unreadable replies. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 8411. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 completed | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 8411 | 0 (0) | 48 KiB | 5.4 MiB | 648 KiB | 0 | 0 | 2 | 2.9% |
| document_versions | 8411 | 0 (0) | 40 KiB | 5.6 MiB | 680 KiB | 0 | 0 | 2 | 2.9% |
| documents | 8411 | 0 (0) | 32 KiB | 2.2 MiB | 266 KiB | 0 | 0 | 2 | 7.1% |
| extracted_facts | 24024 | 0 (0) | 24 KiB | 5.4 MiB | 649 KiB | 0 | 0 | 1 | 3.9% |
| extraction_budgets | 0 | 8411 (8411) | 64 KiB | 64 KiB | 0 KiB | 35 | 24 | 3 | 77.9% |
| extractions | 8411 | 0 (0) | 40 KiB | 6.4 MiB | 771 KiB | 0 | 0 | 1 | 4.4% |
| jobs | 8411 | 0 (0) | 64 KiB | 4.7 MiB | 564 KiB | 0 | 0 | 2 | 4.1% |
| outbox | 8411 | 0 (0) | 24 KiB | 4.8 MiB | 584 KiB | 0 | 0 | 2 | 4.8% |
