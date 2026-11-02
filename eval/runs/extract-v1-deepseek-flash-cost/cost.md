# Extraction cost at harness scale

Issue #38, record 0023. This is a second harness run with the same prompt (`extract-v1`, SHA-256 `eb0203b6…1572149`), model (`deepseek-flash`) and set (`pilot-v1`, frozen at `0bdc408`) as record 0022's run. Its only purpose was to record the tokens each call bills. It was made once, on 2026-11-02 at 18:07 UTC, with `task eval:extract EVAL_RUN=eval/runs/extract-v1-deepseek-flash-cost`. Nothing was chosen or changed from its results. `run.json`, `predictions.jsonl` and the gate's `report.md` are in this directory. `ExtractHarnessTest` checks that the numbers below are priced from `run.json`, and `EvalGateTest` checks that `report.md` is an exact re-score.

## Tokens

52 documents, 52 model calls, and all 52 reported usage.

| | Total | Per document |
| --- | --- | --- |
| Input tokens served from the provider's context cache | 13440 | 258.5 |
| Input tokens not from that cache | 12450 | 239.4 |
| Output tokens | 6541 | 125.8 |

The provider's context cache is DeepSeek's own. It cuts the price of the shared system prompt. It isn't ANVIL's cache, and it works across a key's requests, not across ANVIL tenants.

## Prices

The prices are from <https://api-docs.deepseek.com/quick_start/pricing>, read on 2026-11-02. They are dollars per million tokens for `deepseek-flash` (DeepSeek-V4.1-Flash-1017). The page gives no effective date and says prices may change.

| Rate | Input, cache hit | Input, cache miss | Output |
| --- | --- | --- | --- |
| Off-peak | $0.003 | $0.15 | $0.60 |
| Peak | $0.006 | $0.30 | $1.20 |

Peak hours are 01:00 to 04:00 and 06:00 to 10:00 UTC, Monday to Friday, excluding Chinese public holidays. This run started at 18:07 UTC on a Monday, so it was off-peak.

## Cost

| Case | Whole run (52 documents) | Per document | Per 1,000 documents |
| --- | --- | --- | --- |
| Off-peak, as run | $0.005832 | $0.000112 | $0.11 |
| Peak | $0.011665 | $0.000224 | $0.22 |
| Peak, with no provider cache hit | $0.015616 | $0.000300 | $0.30 |

The last row is the ceiling at these prices for documents like these: every input token at the cache-miss rate, at peak. A document is cut to 500 characters before the call, so input can't grow with document length. Output is capped at 4096 tokens, at most $0.0049 per call at peak.

## Latency

These are model calls only, measured in the harness:

| Run | p50 | p95 | Max |
| --- | --- | --- | --- |
| Record 0022's run (2026-11-02 15:38 UTC) | 1146 ms | 1436 ms | 1659 ms |
| This run (2026-11-02 18:07 UTC) | 1142 ms | 1497 ms | 2039 ms |

Each run took 61 seconds.

## Quality of this run

The gate's report on this run matches record 0022's run:
- F1: 0.990 [0.978, 1.000], against the baseline's 0.758 [0.702, 0.808].
- Paired difference: +0.232 [+0.182, +0.289].
- Attacks: 0 of 12 succeeded, rate 0.000 [0.000, 0.242].
- Citations: 0 of 245 unsupported.
- Gate: PASS.

## Cache and fallback

`ExtractionCostTest.atHarnessScaleTheCacheAndFallbackGiveThePublishedCounts` checks these counts. It runs all 52 documents through the service four times, against the real database, with the model replaying the committed predictions.

| Pass | Model calls | Budget spent | Source | Cost |
| --- | --- | --- | --- | --- |
| Cold: a tenant with a budget of 52 | 52 | 52 | `MODEL` ×52 | as above |
| Warm: the same texts as new documents, same tenant | 0 | 0 | `CACHE` ×52 | $0 |
| Another tenant with no budget, same texts | 0 | 0 | `RULES` ×52, `BUDGET_EXHAUSTED` | $0 |
| Kill-switch off, first tenant with budget | 0 | 0 | `RULES` ×52, `KILL_SWITCH` | $0 |

- **Cache.** A hit needs the same tenant and the same bounded text, prompt and model. It also needs an accepted, unrevoked entry whose facts all pass the checks again. A hit serves the same facts as the call it copies. Its latency is a database read, which isn't measured here.
- **Fallback.** The fallback is the frozen rules baseline, with no model call. Over this set its facts are exactly `baseline.jsonl` (`ExtractionFallbackTest.overTheFrozenSetTheFallbackIsExactlyTheBaseline`). So its quality here is the baseline's: F1 0.758 [0.702, 0.808], with 3 of 12 attacks succeeding (`i01`, `i10`, `i12`). Every fallback result is marked `fallback: true` with its reason.
- **Failed calls.** A call that fails after its budget was taken still counts. Nothing is refunded, so a budget can only run out early, never overspend.
