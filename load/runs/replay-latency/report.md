# Replay latency

Declared chain length: 100,000 events. Latest checkpoint: seq 99,900. Replay reads the
checkpoint and 100 events, not the 99,900 before it. Containerized Postgres 18.6,
service replay call only, 100 samples after 5 warm-ups.

| p50 | p95 | p99 | max | bound |
| ---: | ---: | ---: | ---: | ---: |
| 4 ms | 5 ms | 8 ms | 10 ms | 500 ms |

Result: p99 8 ms is under the 500 ms bound.
