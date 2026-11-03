# Load runs for AI extraction

This directory also holds the replay latency measurement for issue #76 in [`runs/replay-latency/`](runs/replay-latency/report.md). It is produced by `ReplayTest` over a 100,000-event chain on containerized Postgres, not by the extraction load rig.

Issue #79 asks for load results at 140 requests per second, a 10x flood and a soak of 10,000 documents a day. Each must report p95 latency, error rate and table-bloat growth. It also sets a condition: no soak numbers until the full-text index's cost is measured and a budget recorded, and none at all if the budget can't be met. Record 0025's measurement put the index over budget, so this page has steady and flood results and no soak numbers.

## How a run works

`LoadRig` (`task load:steady`, `task load:flood`, `task load:soak`) starts:
- a fresh `postgres:18.6` container with default settings, migrated to the latest schema (which has no full-text index);
- the app, in the same JVM, configured as it is for deployment except for the model;
- k6 1.4.2, pinned by digest, on the host network, running [`extract.js`](extract.js).

Each request submits a document and extracts it: `POST /api/documents`, then `POST /api/documents/{id}/extract`. The documents are the frozen full-v1 texts, each with a unique reference appended, so every extraction is a model call and the cache never answers. Twenty tenants each have a budget too large to run out, and the kill-switch is on. Requests start at a constant arrival rate, so a slow server shows up as latency and as requests k6 couldn't start, rather than as a lower offered load. In the main runs every virtual user is created before the run starts, so a dropped request means all of them were waiting on the server. The two comparison runs start with fewer (`PRE_VUS`) and create more as needed, which caps how many requests wait at once.

The model is `LatencyStubModel`. It makes no network call and costs nothing, but holds each request for a time drawn from the live model's recorded latency: median 1,142 ms, p95 1,497 ms, at most 3 s (record 0023's cost run). It answers with the frozen rules' facts, which the service checks and stores as it would the model's. At 140 requests per second, live calls would cost about $50 to $150 an hour and hit the provider's rate limits. The provider's own latency under load isn't measured here.

The error rate counts every request that wasn't completed: a non-2xx answer, a 30-second timeout (k6 error 1050), a refused or reset connection, an unreadable reply, or a request k6 couldn't start because every virtual user was waiting. A run whose k6 exits with an error writes no result.

Table sizes and tuple counts come from Postgres's statistics views, before the run, at its end, and after 90 seconds idle, which gives autovacuum time to visit. "Wasted" is pgstattuple's dead and free space after the idle period.

App, Postgres and k6 share one 12-CPU host. The numbers describe that host and the stock configuration, with Tomcat's 200 request threads and Hikari's 10 connections. They don't predict a production deployment.

## Results

| Run | Offered | Completed | Server errors | Not started | Error rate | Extract p95 | Submit p95 | Tables grew | Per 1,000 completed | Wasted after idle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [Steady](runs/steady/report.md): 140/s for 5 min, 1,500 VUs | 42,001 | 34,078 | 0 | 7,923 | 18.86% | 9,229 ms | 7,771 ms | 137.2 MiB | 4.0 MiB | at most 4.0% |
| [Steady, 300 VUs at start](runs/steady-300-vus/report.md) | 42,001 | 41,702 | 0 | 299 | 0.71% | 2,216 ms | 964 ms | 167.8 MiB | 4.0 MiB | at most 4.0% |
| [Flood](runs/flood/report.md): 1,400/s for 60 s, 3,000 VUs, then 14/s | 84,841 | 8,411 | 0 | 76,430 | 90.09% | 19,248 ms (recovery 1,520 ms) | 17,212 ms | 34.2 MiB | 4.1 MiB | at most 7.1% |
| [Flood, 1,500 VUs at start](runs/flood-1500-vus/report.md) | 84,842 | 11,392 | 0 | 73,450 | 86.57% | 12,866 ms (recovery 1,514 ms) | 11,664 ms | 46.1 MiB | 4.0 MiB | at most 5.6% |
| Soak: 10,000 over 60 min | none: the full-text index is over budget (record 0025), so the soak refuses to run and no soak numbers are claimed | | | | | | | | | |

"Server errors" counts timeouts, refused or reset connections, 4xx, 5xx and unreadable replies; there were none in any run. "Wasted after idle" leaves out `extraction_budgets`. That table has 20 rows, updated once per call, almost all as HOT updates, and stays at 64 to 72 KiB.

What the runs show:
- **140 per second is at the edge of one process.** Each extraction holds a Tomcat request thread for the whole model call. With 200 threads and a call of about 1.2 s, one process can't complete much more than about 170 extractions a second. The two steady runs differ only in how many requests k6 lets wait at once. Starting with 300 virtual users, 41,702 of 42,001 completed (0.71% not started) with extract p95 2.2 s, already above the model's own 1.5 s. With all 1,500 created up front, a backlog formed and stayed. Throughput fell to about 113 a second, 18.86% couldn't start, and extract p95 reached 9.2 s. So the service doesn't hold 140 per second with a deep queue of waiting clients. Why throughput falls as the queue deepens is not yet explained. The first runs of both, before the review fixes, showed the same: 0.79% and p95 2.7 s, then 27% and 11.5 s.
- **A 10x flood is queued, never refused.** The server answered every request it got, with no error, reset or timeout, but completed only about 125 to 175 a second, with latency up to 20 s. k6 couldn't start most of the flood because all its virtual users were waiting. ANVIL has no admission control: it neither sheds load with `429` or `503` nor bounds its queue. Right after the flood, at 14 a second, extract p95 was back to about 1.5 s.
- **Table growth per completed request is the same in every run,** about 4.0 MiB per 1,000, so it depends on what is stored, not on load. At a soak day's 10,000 documents that would be about 40 MiB, but no soak was run. Bloat stayed low because the hot path only inserts. The one table updated per call, `extraction_budgets`, stays small through HOT updates.
- **No soak.** Record 0025's budget for the full-text index wasn't met by either variant, so `task load:soak` refuses to run. A soak made earlier under a measurement that undercounted the index's cost was withdrawn.
