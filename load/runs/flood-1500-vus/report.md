# Load run: flood

Offered: a 10x flood: 1,400 extraction requests per second for 60 seconds, then 14 per second for 60 seconds to show recovery. Each request submits a document and extracts it.
k6 settings changed for this run: {PRE_VUS=1500}.
Run 2026-11-03T00:58:27Z to 2026-11-03T01:01:10Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 84842 | 11392 | 0 | 73450 | 86.57% | 10706 ms | 12866 ms | 13365 ms | 14485 ms | 11664 ms |

- Flood: 10551 requests, 0 errors; extract p50 10929 ms, p95 12898 ms.
- Recovery: 841 requests, 0 errors; extract p50 1166 ms, p95 1514 ms.
- Errors by kind: 0 timeouts (30 s), 0 connection errors (refused or reset), 0 5xx, 0 4xx, 0 unreadable replies. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 11392. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 completed | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 11392 | 0 (0) | 48 KiB | 7.2 MiB | 647 KiB | 0 | 0 | 2 | 2.7% |
| document_versions | 11392 | 0 (0) | 40 KiB | 7.4 MiB | 666 KiB | 0 | 0 | 2 | 2.7% |
| documents | 11392 | 0 (0) | 32 KiB | 3.0 MiB | 265 KiB | 0 | 0 | 2 | 5.6% |
| extracted_facts | 32524 | 0 (0) | 24 KiB | 7.0 MiB | 626 KiB | 0 | 0 | 2 | 1.2% |
| extraction_budgets | 0 | 11392 (11392) | 64 KiB | 64 KiB | 0 KiB | 55 | 0 | 4 | 80.4% |
| extractions | 11392 | 0 (0) | 40 KiB | 8.7 MiB | 775 KiB | 0 | 0 | 2 | 2.8% |
| jobs | 11392 | 0 (0) | 64 KiB | 6.4 MiB | 567 KiB | 0 | 0 | 2 | 3.5% |
| outbox | 11392 | 0 (0) | 24 KiB | 6.7 MiB | 600 KiB | 0 | 0 | 2 | 4.3% |
