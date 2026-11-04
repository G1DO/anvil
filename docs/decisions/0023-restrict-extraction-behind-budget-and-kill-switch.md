# 0023 — Restrict AI extraction behind a tenant cache, a call budget and a kill-switch

Status: accepted 2026-11-04, proposed 2026-11-02 (#38). Builds on the frozen eval gate (record 0021) and single bounded extraction (record 0022). Record 0024 (#79) brings the larger injection set this decision said to revisit on; it doesn't change the decision.

## Context

Each extraction is a paid call to a third party, and it holds the request for up to a second or more. Under record 0022 a new version cost one call, and nothing capped how many calls a tenant could make or let an operator stop them. A bad model result had no way to be withdrawn. Nobody had measured cost, so nobody could decide from evidence whether extraction should stay.

## Decision: restrict

AI extraction stays, but only within these limits:
- **No call without a budget.** Each model call takes one call from the tenant's budget. A tenant has no budget until an operator grants one, so no tenant text reaches the provider by default.
- **A kill-switch an operator can flip at any time.** It applies to every process at once, with no restart.
- **The rules fallback.** With no budget left, the switch off, or the result revoked, the frozen rules answer with no model call, and the result is marked as fallback.
- **The eval gate** (record 0021) keeps scoring the committed run on every change and blocks a regression.
- **No real tenant traffic to DeepSeek** until its terms and data handling are checked (record 0022). Granting a budget is the step that sends text. For now only the harness's fictional documents go.

### The evidence

| Measure | Value | Source |
| --- | --- | --- |
| F1 | 0.990 [0.978, 1.000] against the baseline's 0.758 [0.702, 0.808] | record 0022's run, re-scored by CI |
| Paired F1 difference | +0.232 [+0.182, +0.289] | same |
| Precision, recall | 0.995 [0.985, 1.000], 0.986 [0.970, 1.000] | same |
| Attack success rate | 0 of 12, 0.000 [0.000, 0.242] (Wilson); baseline 3 of 12, 0.250 [0.089, 0.532] | same |
| Unsupported citations | 0 of 245 | same |
| Cost per document | $0.000112 off-peak, $0.000224 at peak, at most $0.000300 with no provider cache hit | [cost report](../../eval/runs/extract-v1-deepseek-flash-cost/cost.md) |
| Cost per 1,000 documents | $0.11 to $0.30 | same |
| Model-call latency, p95 | 1436 ms (record 0022's run), 1497 ms (cost run) | both runs' `run.json` |
| Cache hit | 0 model calls, $0 | `ExtractionCostTest` |
| Fallback | 0 model calls, $0, quality equal to the baseline: F1 0.758 [0.702, 0.808], 3 of 12 attacks | `ExtractionFallbackTest`, `ExtractionCostTest` |

The cost run was a second harness run with the same prompt, model and set, made once to record billed tokens. It reproduced the first run's scores exactly, and nothing was chosen from it (record 0021's never-tuned-on rule).

### Why restrict, not keep or remove

- **Not remove.** The quality gain is large and clear: the difference interval's lower bound is +0.182, far from 0. The model blocked all three attacks the baseline fell for. It costs a fraction of a cent per document and answers in about 1.5 seconds at p95. Removing it would give up that quality to save almost nothing.
- **Not keep without limits:**
  - The injection evidence is 12 cases. 0 of 12 still allows a true rate up to 0.242, and the citation check can't stop a planted value that is also a plausible fact (record 0022).
  - DeepSeek's retention and training terms are unchecked.
  - Cost is tiny per call but unbounded in count without a budget.

  The limits make each of those risks an operator decision per tenant, with the rules as the safe floor.
- **No non-inferiority margin.** Record 0021 left #38 room to add one. Choosing a margin from this set would be tuning on it, so the gate stays as it is.
- **Revisit when** DeepSeek's terms are checked, a larger injection set is frozen (#79), or a new prompt or model is proposed. Any of these can move the decision to keep or remove.

## How it works

The service decides a version that has no result in this order. Each step runs only if the one before didn't decide it (`ExtractionService`):
1. **Screens.** Poison is quarantined with no model call, as in record 0022, and no budget is spent.
2. **Kill-switch.** `extraction_switch.model_calls_enabled` is read before every call. When it is false, or the row is missing, the rules answer with reason `KILL_SWITCH`.
3. **Cache.** The cache key is the tenant, the SHA-256 of the bounded text the model would see, the prompt hash and the model.
   - A hit copies an earlier result of the same tenant, with no model call and no budget spent. That result must be a `MODEL` result, `ACCEPTED` and unrevoked, and each of its facts must pass the citation and value checks again against this document's text.
   - **Poisoned entries are never served.** A quarantined entry isn't a candidate. An entry with any fact that fails the checks again isn't reused: the document goes on to the budget and the model.
   - **No cross-tenant hit.** The lookup is limited to the caller's tenant and runs under RLS, so identical text in another tenant is never a hit (record 0022's rule).
   - The copy is stored with source `CACHE` and `cache_of` naming its original.
4. **Budget.** `extraction_budgets` holds each tenant's remaining calls. One atomic `UPDATE … WHERE calls_remaining > 0` takes a call and commits before the call, so concurrent requests in any number of processes can't overspend. When no call is left, or the tenant has no row, the rules answer with reason `BUDGET_EXHAUSTED`.
   - A failed call still counts. Nothing is refunded, so a budget can only run out early.
   - A trigger stops the runtime role from raising `calls_remaining` or lowering `calls_used`, and its grant is `UPDATE` on `calls_remaining`, `calls_used` and `updated_at` only.
5. **Model.** As in record 0022.

The rest of the design:
- **The fallback** is `RulesBaseline`, the frozen regex extractor from record 0021, moved from the test sources to `main` with its rules unchanged.
  - It runs on the same bounded text, and its facts go through the same checks, so a fallback fact also has an exact citation. Over the frozen set it gives exactly `baseline.jsonl`.
  - A fallback result is stored under the rules' own model ID (`rules-baseline`) and hash (`RulesBaseline.SHA256`), with source `RULES` and its reason.
  - Both routes report `fallback: true`, and `POST …/extract` also reports `fallback_reason`.
  - Because the fallback doesn't take the model's key, the model can still extract the version once calls are allowed. Its result is then served in place of the fallback.
- **Revoked entries are never served.** An operator revokes a result by inserting a row into `extraction_revocations`. A copy counts as revoked when its original is.
  - A revoked result isn't served: the facts route reads `NOT_EXTRACTED`.
  - It is never reused as a cache entry.
  - Its version is answered by the rules with reason `REVOKED`, with no model call. A new version extracts afresh.
  - Rules results are deterministic and aren't subject to revocation.
- **Operator statements.** RLS `FORCE` applies to the owner, so each statement runs in a transaction with `SET LOCAL app.tenant = '<tenant>'` as `anvil_migrator`. Acting across all tenants at once needs a role that bypasses RLS.

  ```sql
  -- Grant or top up a tenant's budget.
  INSERT INTO extraction_budgets (tenant_id, calls_remaining) VALUES ('<tenant>', 1000)
      ON CONFLICT (tenant_id) DO UPDATE
      SET calls_remaining = extraction_budgets.calls_remaining + EXCLUDED.calls_remaining, updated_at = now();
  -- Kill-switch (no tenant needed): FALSE stops every model call; TRUE allows them again.
  UPDATE extraction_switch SET model_calls_enabled = FALSE, reason = '<why>', changed_at = now();
  -- Revoke a result and every copy made from it.
  INSERT INTO extraction_revocations (tenant_id, extraction_id, reason)
      SELECT tenant_id, id, '<why>' FROM extractions
      WHERE tenant_id = '<tenant>' AND (id = '<extraction id>' OR cache_of = '<extraction id>')
      ON CONFLICT DO NOTHING;
  ```
- **Schema** (V9, additive only):
  - New columns on `extractions`: `source`, `content_sha256`, `cache_of` and `fallback_reason`, with checks tying them together, plus a partial index for cache lookups.
  - New tables:
    - `extraction_revocations` and `extraction_budgets`: RLS `FORCE` with the same `tenant_isolation` policy.
    - `extraction_switch`: no tenant data and no RLS, like `tenants`.
  - The runtime role can read revocations and the switch, and can only spend its budget.
  - V8's key `(tenant_id, version_id, prompt_sha256, model)` is unchanged.
- **The API** (additive): `POST …/extract` adds `source`, `fallback` and `fallback_reason`; `GET …/facts` adds `source` and `fallback`.
- **The harness** records the tokens each call bills (`prompt_cache_hit_tokens`, `prompt_cache_miss_tokens`, `completion_tokens`) in `run.json`. A missing or malformed count is recorded as no usage, never guessed.

Alternatives considered:
- **A budget in configuration, counted in memory.** Each process would have its own count, so N processes could spend N budgets, and changing it would need a restart. Rejected for the database row.
- **A budget in tokens or dollars.** Tokens are known only after the call, so a token budget can be overshot by one call's worth, and a price-based one changes when prices do. The pilot's calls are close to uniform (about 500 tokens in and 126 out), so a call count is predictable and checked before the call. Tokens are published per run instead.
- **A kill-switch in configuration.** It needs a restart in each process and can't be flipped mid-incident. Rejected for the database row, read before every call.
- **A human review queue as the fallback.** No reviewer workflow exists. The rules give an answer at once with known quality, and the record of the fallback reason lets a later review queue pick those results up.
- **A separate cache table.** It would duplicate the facts already stored per version. The extraction rows are the cache, and the copy keeps per-version facts and the routes unchanged.
- **Revocation as a column on `extractions`.** The runtime role is append-only on that table, and re-extracting a revoked version under the same key would need V8's unique key relaxed, which is a destructive schema step. An append-only revocations table needs neither.
- **Refunding a failed call.** A timeout may still be billed, and a refund path would let a crash or a flood of failures spend more than the budget.
- **Serving a cache hit while the switch is off.** It would cost nothing, but the switch exists to stop new model-derived answers, so it overrides the cache.

## Consequences

- **Proven by:**
  - `ExtractionCostTest`:
    - a hit in the same tenant makes no model call and spends no budget;
    - the key holds the prompt and model;
    - there is no cross-tenant hit, even when one would save a call;
    - quarantined entries and entries with a fact that fails again are never reused;
    - a revoked result and its copies are never served or reused, and its version falls back with no call;
    - an exhausted or missing budget gives the rules with no call until topped up, after which the model's result replaces the fallback;
    - a failed call still counts;
    - poison is screened before the budget or switch;
    - the budget holds across two processes;
    - the kill-switch, or a missing switch row, gives the rules for every tenant;
    - the runtime role's grants;
    - the harness-scale counts in the cost report.
  - `ExtractionFallbackTest`: the content key is the bounded text, a cached entry is reused only when every fact passes again, the fallback makes no model call and keeps only checked facts, and over the frozen set it is exactly the baseline.
  - `RlsLeakTest`: RLS `FORCE` on the two new tenant tables, and the runtime role can only spend its own budget.
  - `DeepSeekExtractionModelTest` and `ExtractHarnessTest`: billed tokens read and never made up, summed per run, and the cost report priced from the cost run.
  - `EvalGateTest`: both committed runs re-score exactly.
- **A cross-process race can still spend two calls on one version,** as in record 0022. Both calls come out of the budget, and the unique key keeps one result.
- **The switch has a check-then-call gap.** A call admitted just before the switch flips still runs. The switch stops calls from the next request on.
- **A revoked version can't be extracted by the model again** under the same prompt and model; it is answered by the rules. Resubmitting the document gives a new version, which extracts afresh.
- **Fallback quality is the baseline's,** which is biased low on this set (record 0021) and lets 3 of 12 attacks through. A tenant on the fallback gets lower recall and the baseline's exposure to planted values.
- **Prices are read from DeepSeek's page on one date** and can change. The cost report gives the tokens, so the cost can be recomputed.
- **Cache-hit latency isn't measured.** A hit is a database read and a re-check of at most 64 facts, with no model call.
