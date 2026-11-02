# Load run: soak

Offered: 10,000 documents, the soak target for a day, spread evenly over 60 minutes (2.8 per second). Each request submits a document and extracts it.
Run 2026-11-02T21:23:18Z to 2026-11-02T22:23:20Z, k6 grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7.
Model: LatencyStubModel: median 1142 ms, p95 1497 ms, at most 3000 ms (the cost run's model-call latency); no network. Postgres: postgres:18.6, default settings. Host: 12 CPUs, app, Postgres and k6 on one host.
GIN budget (record 0025): measured 2026-11-02T21:09:01Z, within budget with gin_fastupdate_on; the index was in place for this run.

| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10001 | 10001 | 0 | 0 | 0.00% | 1167 ms | 1527 ms | 1708 ms | 2135 ms | 21 ms |

- Errors by kind: 0 timeouts (30 s), 0 5xx, 0 4xx. Dropped: k6 had no free virtual user to start the request at its time.
- Model calls: 10001. Results marked as fallback: 0.

Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after 90 s idle; wasted space is pgstattuple's dead plus free percent after the idle period):

| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | Dead after idle | Autovacuums | Wasted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| audit_events | 10001 | 0 (0) | 48 KiB | 6.4 MiB | 654 KiB | 0 | 0 | 4 | 3.9% |
| document_versions | 10001 | 0 (0) | 40 KiB | 6.6 MiB | 670 KiB | 0 | 0 | 4 | 3.7% |
| documents | 10001 | 0 (0) | 32 KiB | 2.6 MiB | 262 KiB | 0 | 0 | 4 | 7.6% |
| extracted_facts | 28538 | 0 (0) | 40 KiB | 6.6 MiB | 667 KiB | 0 | 0 | 8 | 1.7% |
| extraction_budgets | 0 | 10001 (10001) | 64 KiB | 64 KiB | 0 KiB | 14 | 11 | 60 | 77.9% |
| extractions | 10001 | 0 (0) | 40 KiB | 7.6 MiB | 778 KiB | 0 | 0 | 4 | 4.7% |
| jobs | 10001 | 0 (0) | 64 KiB | 5.5 MiB | 555 KiB | 0 | 0 | 4 | 4.0% |
| outbox | 10001 | 0 (0) | 24 KiB | 5.7 MiB | 585 KiB | 0 | 0 | 4 | 4.8% |
