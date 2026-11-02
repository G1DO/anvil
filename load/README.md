# Load runs for AI extraction

Issue #79 asks for load results at 140 requests per second, a 10x flood and a soak of 10,000 documents a day. Each must report p95 latency, error rate and table-bloat growth. Record 0025 adds a condition: no soak numbers until the full-text index's cost was measured and met its budget.

## How a run works

`LoadRig` (`task load:steady`, `task load:flood`, `task load:soak`) starts:
- a fresh `postgres:18.6` container with default settings, migrated to the latest schema, including the V10 full-text index;
- the app, in the same JVM, configured as it is for deployment except for the model;
- k6 1.4.2, pinned by digest, on the host network, running [`extract.js`](extract.js).

Each request submits a document and extracts it: `POST /api/documents`, then `POST /api/documents/{id}/extract`. The documents are the frozen full-v1 texts, each with a unique reference appended, so every extraction is a model call and the cache never answers. Twenty tenants each have a budget too large to run out, and the kill-switch is on. Requests start at a constant arrival rate, so a slow server shows up as latency and as requests k6 couldn't start, rather than as a lower offered load. Every virtual user is created before the run starts, so a dropped request means all of them were waiting on the server.

The model is `LatencyStubModel`. It makes no network call and costs nothing, but holds each request for a time drawn from the live model's recorded latency: median 1,142 ms, p95 1,497 ms, at most 3 s (record 0023's cost run). It answers with the frozen rules' facts, which the service checks and stores as it would the model's. At 140 requests per second, live calls would cost about $50 to $150 an hour and hit the provider's rate limits. The provider's own latency under load isn't measured here.

The error rate counts every request that wasn't completed: a non-2xx answer, a 30-second timeout, or a request k6 couldn't start because every virtual user was waiting.

Table sizes and tuple counts come from Postgres's statistics views, before the run, at its end, and after 90 seconds idle, which gives autovacuum time to visit. "Wasted" is pgstattuple's dead and free space after the idle period.

App, Postgres and k6 share one 12-CPU host. The numbers describe that host and the stock configuration, with Tomcat's 200 request threads and Hikari's 10 connections. They don't predict a production deployment.

## Results

| Run | Offered | Completed | Server errors | Not started | Error rate | Extract p95 | Submit p95 | Tables grew | Per 1,000 requests | Wasted after idle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [Soak](runs/soak/report.md): 10,000 over 60 min | 10,001 | 10,001 | 0 | 0 | 0.00% | 1,527 ms | 21 ms | 40.7 MiB | 4.1 MiB | at most 7.6% |
| [Steady](runs/steady/report.md): 140/s for 5 min | 42,001 | 30,563 | 0 | 11,438 | 27.23% | 11,508 ms | 9,762 ms | 124.8 MiB | 4.1 MiB | at most 3.9% |
| [Flood](runs/flood/report.md): 1,400/s for 60 s | 84,840 | 9,005 | 0 | 75,835 | 89.39% | 18,617 ms | 17,189 ms | 38.1 MiB | 4.2 MiB | at most 6.9% |

"Wasted after idle" leaves out `extraction_budgets`. That table has 20 rows, updated once per call, almost all as HOT updates, and stayed at 64 to 72 KiB in every run.

What the runs show:
- **A day of soak traffic is easy.** At 2.8 requests per second, extract p95 is 1,527 ms, the stub model's own 1,497 ms plus about 30 ms of ANVIL and Postgres. Every request completed. The tables, the V10 full-text index included, grow about 4.1 MiB per 1,000 documents, so about 41 MiB per soak day. Autovacuum visited every table, and after 90 seconds idle no table but the budgets kept more than 7.6% dead or free space.
- **140 per second is at the edge of one process.** Each extraction holds a Tomcat request thread for the whole model call. With 200 threads and a call of about 1.2 s, one process can't complete much more than about 170 extractions a second. The two steady runs differ only in how many virtual users k6 could keep waiting. The [first run](runs/steady-300-vus/report.md), started with 300 and growing as needed, completed 41,670 of 42,001 (0.79% not started) with extract p95 2,665 ms. The final run created all 1,500 up front. A backlog formed and stayed, throughput fell to about 102 a second, and extract p95 reached 11.5 s. So the service doesn't hold 140 per second with a deep queue of waiting clients. Why throughput falls when the queue is deep is not yet explained.
- **A 10x flood is queued, never refused.** The server answered every request it got, with no 5xx and no timeout, but completed only about 136 a second ([first flood run](runs/flood-1500-vus/report.md): 178), with latency up to 21 s. k6 couldn't start 89% of the flood because all 3,000 of its virtual users were waiting. ANVIL has no admission control: it neither sheds load with `429` or `503` nor bounds its queue. Right after the flood, at 14 a second, extract p95 was back to 1,545 ms.
- **Table growth per request is the same in every run,** about 4.1 to 4.2 MiB per 1,000, so it depends on what is stored, not on load. Bloat stayed low because the hot path only inserts. The one table updated per call, `extraction_budgets`, stays small through HOT updates.
- **The soak ran only after record 0025's verdict,** with the index in place. Its extract p99 (1,708 ms) and max (2,135 ms) show no store stall beyond the model's own latency. The budget's limits themselves were measured by `task load:gin`, not under this load.
