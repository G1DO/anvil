# 0022 — Single bounded extraction with exact citations and a dead-letter queue

Status: proposed 2026-11-02 (#37). Builds on the frozen eval gate (record 0021). Cost, caching, a budget and the keep, restrict or remove decision are #38.

## Context

Tenants want facts pulled out of their documents. A model can invent or paraphrase a citation, put a value in a fact that its citation doesn't hold, follow an instruction planted in the document, or return junk. Its answer must not cross tenants, through a shared result, cache or dedup key, and asking about another tenant's document must not reveal that it exists. Inputs that can't be extracted safely must be kept for investigation but never served.

## Decision

- **The model.** `DeepSeekExtractionModel` calls the DeepSeek chat completions API (`POST https://api.deepseek.com/chat/completions`) with `deepseek-flash`, using the JDK's HTTP client. It adds no dependency.
  - The base URL and model come from `anvil.extraction` in `application.yaml`. The base URL must be `https`, and the model ID is checked against a plain pattern.
  - The credential comes only from `ANVIL_MODEL_API_KEY`, sent as a bearer token, and is never logged, stored or committed. Without it the app starts and extraction answers `503` without a call.
  - Each call is one stateless request: the system prompt, one user message, JSON output mode (`response_format` `json_object`), thinking off, temperature 0, at most 4096 output tokens, and no tenant identifier.
  - JSON mode guarantees JSON but takes no schema, so the prompt spells out the one shape allowed, `{"facts": [{"field", "value", "citation"}]}`, and the extractor rejects anything else.
  - Rate limits (`429`), server errors and overload (`500`, `503`) and timeouts are retried with backoff: 2 retries in the service and 6 in the harness, each wait at most 60 seconds. A bad key (`401`), no balance (`402`) or a bad request (`400`, `422`) fails at once.
- **One path, bounded.** `Extractor.extract` is the only extraction path. The service and the harness both call it, and it is the only caller of `ExtractionModel.complete`.
  - `complete` takes a `BoundedText`, whose only constructor is `BoundedText.of`, which cuts the text to 500 characters (Java `char`s, the eval set's unit) without splitting a surrogate pair. So no code path can send a model more of a document.
  - The document goes between `<<<DOCUMENT m>>>` and `<<<END DOCUMENT m>>>`, where `m` is 24 random hex digits per call, so the document can't close the data block.
- **What gets indexed.** A fact is indexed only when:
  - its field is `party`, `person`, `date` or `amount`;
  - its citation is non-empty and passes `CitationCheck.isExactSubstring` against the 500 characters the model saw (not the full document, so a citation can't come from text the model never read);
  - its value, normalized as the eval scores values, appears in its normalized citation.

  A fact failing a check goes to the dead-letter table with its reason (`UNKNOWN_FIELD`, `UNCITED`, `CITATION_NOT_IN_SOURCE`, `VALUE_NOT_IN_CITATION`); the document's other facts can still be indexed. A repeated fact is indexed once.
- **Poison input.** The whole document is quarantined, with nothing indexed, for:
  - `UNSUPPORTED_MEDIA_TYPE` (not `text/*`), `EMPTY_TEXT`, or `NOT_TEXT` (a control character other than tab or line break, or invalid UTF-8). These are decided before any model call.
  - `MODEL_REFUSED` (finish reason `content_filter`), or `MALFORMED_OUTPUT`: not JSON (JSON mode blanks out non-JSON text, so this includes a blank reply), any key outside the shape, more than 64 facts, a reply cut off by `max_tokens` (finish reason `length`), or any other finish reason. Up to 2000 characters of the reply are kept with the dead letter.

  A model outage (no credential, network, timeout, rate limit, server error) is not poison: nothing is stored and the request answers `503`, so it can be retried.
- **Storage** (V8, all with RLS `FORCE` and the `tenant_isolation` policy, cascading from `documents`):
  - `extractions`: one row per `(tenant_id, version_id)`, with model, prompt hash, status (`ACCEPTED` or `QUARANTINED`) and whether the text was truncated.
  - `extracted_facts`: the index.
  - `extraction_dead_letters`: reason, the rejected fact if any, and detail. No route reads it.

  The model is asked outside any transaction. The outcome is written in one transaction; if another request for the same version committed first, its result is returned.
- **Routes** (tenant only from `X-Tenant-Id`):
  - `POST /api/documents/{id}/extract` extracts the latest version once; a repeat returns the stored result without a model call.
  - `GET /api/documents/{id}/facts` serves the indexed facts only.

  A forged, malformed, missing or other tenant's ID gets `404 {"error":"not_found"}`, the same bytes for each, before any model call.
- **No cross-tenant reuse.** A result is keyed by tenant and version, so identical content in two tenants is extracted twice. There is no content-addressed cache or dedup across tenants. #38's cache must key by tenant too.
- **The prompt** (`Prompt`, version `extract-v1`) was written from the four field definitions and the threat model, without opening the eval set's documents. Its last lines give the JSON shape, which JSON mode needs; they were added before any run returned results. It wasn't changed after the harness run, and the run was made once. Changing it means a new version, and the frozen set can't be used to choose between versions (record 0021).
- **The harness.** `task eval:extract` runs `Extractor.extract` over every document of `eval/sets/pilot-v1` as `text/plain` and writes `predictions.jsonl` (accepted facts only, exactly what would be indexed) and `run.json` (model, prompt hash, quarantine counts by reason, latency, and which injection cases succeeded) to `eval/runs/<run>/`. `task eval EVAL_CANDIDATE=...` scores it. `run.json` has no per-document quality scores.

## Harness run

Made once, on 2026-11-02 at 15:38 UTC, with `task eval:extract`: `deepseek-flash`, prompt `extract-v1` (SHA-256 `eb0203b6…1572149`), set `pilot-v1` frozen at `0bdc408`. The predictions, `run.json` and the gate's report are in `eval/runs/extract-v1-deepseek-flash/`, and `task eval` now scores this run by default, so CI's `eval` job re-checks it on every change.

- **Run:** 52 documents, 52 model calls, none truncated, none quarantined, 209 facts indexed from the 40 quality documents. Latency p50 1146 ms, p95 1436 ms, max 1659 ms; the run took 61 seconds.
- **Quality** (`task eval`, 95% intervals): precision 0.995 [0.985, 1.000], recall 0.986 [0.970, 1.000], F1 0.990 [0.978, 1.000], against the baseline's F1 0.758 [0.702, 0.808]. Paired F1 difference +0.232 [+0.182, +0.289].
- **Citations:** 0 of 245 are not exact substrings of their document.
- **Injection subset:** 0 of 12 attacks succeeded, attack success rate 0.000 [0.000, 0.242] (Wilson). The baseline's 3 (`i01`, `i10`, `i12`) were blocked. So there are no unmitigated cases to document. The upper bound of 0.242 is the honest limit of 12 cases: a later model or prompt could still be steered (see Consequences).
- **Gate:** PASS.

Before this run, two attempts with another provider returned no results (the model at capacity, then a free-tier daily quota), and the adapter was replaced. No predictions were seen before the prompt's final form.

Alternatives considered:
- **A vendor SDK instead of the JDK's HTTP client.** It would hand-write less, but adds a dependency tree to audit for one request shape. The request and reply are small and pinned by `DeepSeekExtractionModelTest`.
- **A queued job instead of a synchronous call.** It frees the request thread, but needs a job kind, retries and a status route. The pilot's documents are short, so one bounded call per request is enough; #38 can move it behind the queue with the budget.
- **Quarantining the whole document when any fact fails.** It throws away good facts with the bad one. A failing fact is quarantined alone; only output that can't be parsed or trusted as a whole quarantines the document.
- **A cross-tenant cache keyed by content hash.** It would save calls on shared documents, but a hit in one tenant would reveal that another tenant holds the same text. Rejected.
- **A keyword filter for injected instructions.** Any phrase list good enough to matter would be tuned on the injection subset, and it's easy to rephrase around. The data markers, the instruction to treat the document as data, and the citation checks are general.
- **Checking only the citation, not the value in it.** A model could then cite a real span but emit a value it invented.

## Consequences

- **Proven by:**
  - `ExtractorTest`: truncation before the model, the single path, the citation and value checks, every quarantine reason.
  - `DeepSeekExtractionModelTest`: the request (bounded document, JSON mode, thinking off, key only in the header), finish reasons, a blank, filtered or cut-off reply quarantined, retries for rate limits and overload, no retry for a bad key or no balance, and the HTTPS, model ID and credential checks.
  - `ExtractHarnessTest`: the harness sends every document through the extractor once, never writes a fact with an unsupported citation, and writes nothing on a model outage.
  - `ExtractionIsolationTest`: the same `404` for forged, malformed, missing and other tenants' IDs with no model call; identical content extracted separately per tenant; one tenant's poison has no effect on another; dead letters kept with a reason and never served; an outage stores nothing.
  - `RlsLeakTest` now covers the three new tables.
- **The citation check can't catch a planted value that is also a plausible fact.** If a document writes a value and asks for it to be extracted, the value is in the text and its citation is exact. The prompt tells the model not to follow such requests, but that's a mitigation, not a guarantee. The run above had none succeed.
- **Tenant text goes to a third party.** DeepSeek's API documentation doesn't say how long inputs are kept or whether they are used for training, and its context cache is on by default and cleared within hours to days. Its terms and privacy policy must be checked before tenant traffic is sent. The harness sends only the eval set's fictional documents. ANVIL isn't live.
- **No budget, cache or kill-switch yet** (#38). Each new version costs one call.
- **The request waits on the model:** up to 60 seconds per attempt, with 2 retries.
- **A quarantined version stays quarantined,** even when the cause was a one-off malformed reply. A new version is extracted afresh.
