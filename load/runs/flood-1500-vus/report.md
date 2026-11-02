# Load run: flood

Offered: a 10x flood: 1,400 extraction requests per second for 60 seconds, then 14 per second for 60 seconds to show recovery. Each request submits a document and extracts it.
Run 2026-11-02T21:18:34Z to 2026-11-02T21:21:16Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 84840 | 11570 | 0 | 73270 | 86.36% | 10445 ms | 12473 ms | 12905 ms | 13561 ms | 11206 ms |

- Flood: 10729 requests, 0 errors; extract p50 10586 ms, p95 12508 ms.
- Recovery: 841 requests, 0 errors; extract p50 1165 ms, p95 1525 ms.
- Errors by kind: 0 timeouts (30 s), 0 5xx, 0 4xx. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 11570. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 11570 | 0 (0) | 48 KiB | 7.3 MiB | 644 KiB | 0 | 0 | 2 | 2.7% |
| document_versions | 11570 | 0 (0) | 40 KiB | 7.5 MiB | 663 KiB | 0 | 0 | 2 | 2.6% |
| documents | 11570 | 0 (0) | 32 KiB | 3.1 MiB | 270 KiB | 0 | 0 | 2 | 5.6% |
| extracted_facts | 33027 | 0 (0) | 40 KiB | 9.0 MiB | 794 KiB | 0 | 0 | 2 | 1.8% |
| extraction_budgets | 0 | 11570 (11570) | 64 KiB | 64 KiB | 0 KiB | 69 | 0 | 4 | 80.3% |
| extractions | 11570 | 0 (0) | 40 KiB | 8.9 MiB | 785 KiB | 0 | 0 | 2 | 2.3% |
| jobs | 11570 | 0 (0) | 64 KiB | 6.4 MiB | 559 KiB | 0 | 0 | 2 | 3.6% |
| outbox | 11570 | 0 (0) | 24 KiB | 6.7 MiB | 589 KiB | 0 | 0 | 2 | 4.3% |
