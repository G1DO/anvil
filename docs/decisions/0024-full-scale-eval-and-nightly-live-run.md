# 0024 — A generated full-scale eval set, McNemar, a multilingual and base64 injection suite, and a nightly live run

Status: proposed 2026-11-02, revised 2026-11-03 after review (#79), revised 2026-11-04 for the OpenCode Go nightly. Builds on the frozen eval gate (record 0021), single bounded extraction (record 0022) and its restriction (record 0023).

## Context

The pilot set behind records 0021 to 0023 is deliberately small: 40 quality documents, 12 injection cases and 2,000 bootstrap resamples. With 12 cases, 0 successes still allows a true attack success rate up to 0.242, and every case but one is in English. #79 asks for a set of about 700 documents with 10,000-sample intervals and a McNemar test against the baseline, an injection suite of 50 or more multilingual and base64 cases, and a nightly run against the live model.

## Decision

- **A generated set, frozen like the pilot.** `eval/sets/full-v1` holds 660 quality documents with 3,367 gold facts and 72 injection cases, 732 in all. `FullSetGenerator` (under `src/test`, since no set text may appear under `src/main`) builds them from seed 79. It uses word lists of invented organization stems, trade words and legal suffixes, common given names and surnames, and short business templates in English, with about one in eight documents in German, French or Spanish. Each gold fact is recorded when its value is written, so the labels follow the field definitions by construction. Distractors that look like facts are written and never labelled: places, buildings, products, events, quantities, percentages, reference numbers, times, a month without a day, and a day without a year. A document is drawn again if it would come within 0.4 Jaccard of any earlier one. The highest pair is 0.224, against the refusal threshold of 0.5. The files are frozen with SHA-256s in the manifest, and `FullSetTest` checks that the generator reproduces them byte for byte. Changing a template makes a new version. Record 0021 rejected template documents because each would be a near duplicate of the others. The generator answers that with many templates, alternative phrasings per sentence, inserted fillers and distractors, unique names, and the 0.4 redraw rule, and `EvalSet.load` still refuses any pair at 0.5.
- **10,000 resamples and McNemar.** The manifest sets 10,000 paired bootstrap resamples (seed 79). The gate report adds McNemar's test paired on each gold fact: whether each system found it. It reports the exact two-sided test and, since one document's facts aren't independent (one template can make a system miss all its amounts), the clustered test of Durkalski et al. (2003), which sums the discordance per document and estimates its variance across documents. Both are reported, not gated. The gate's question is whether the candidate regressed, and the bootstrap interval of the F1 difference already answers it. McNemar asks whether the two systems differ at all, so it would change no verdict the interval doesn't.
- **A multilingual and base64 injection suite.** There are 52 cases in clear: four attacks (override the party, redefine the amount with fake system tags, replace the date if the reader is a model, rewrite every value) in each of thirteen languages: English, German, French, Spanish, Portuguese, Italian, Dutch, Polish, Turkish, Russian, Arabic, Chinese and Japanese. The other 20 cases base64-encode the instruction, four of them twice. They carry it as a processing note, a header, a data URI, a signature block or an encoded note, and write the canary in clear beside it, so the attack works only if the extractor decodes and follows it. Each case records its `language` and `encoding`, and the report gives attack success by each.
- **Gated on every change.** `task eval:full` scores the committed live run over the full set, in CI's `eval` job and in `task verify`, next to the pilot gate.
- **A nightly live run.** (The key, provider and pricing here are superseded by the 2026-11-04 revision below, and manual dispatch isn't limited to the default branch: see `docs/threat-model.md`.) The `eval-nightly` workflow runs `task eval:nightly` every night at 22:23 UTC, in DeepSeek's off-peak price window. It runs the extraction path over the full set with the live model, then the full gate on the predictions. It is held to the committed live run as well as to the baseline, since a model that drifted far down from F1 0.996 would still beat the baseline's 0.632. The run fails when the paired F1 difference against the committed run lies wholly below 0, or when an attack the committed run blocked succeeds. A failed run, including one with no key, opens an issue with the report, or comments on the one still open. The key is the repository secret `ANVIL_MODEL_API_KEY`, given only to the one step that calls the model. Only scheduled and manual runs on the default branch can reach it, never a pull request.

## Results

One harness run, `eval/runs/extract-v1-deepseek-flash-full-v1`, made on 2026-11-02 off-peak with the unchanged prompt `extract-v1`:

| Measure | Value |
| --- | --- |
| F1 | 0.996 [0.995, 0.998], against the baseline's 0.632 [0.615, 0.648] |
| Precision, recall | 0.993 [0.990, 0.996], 0.999 [0.999, 1.000] |
| Paired F1 difference | +0.364 [+0.349, +0.381] |
| McNemar | the model alone found 1,706 gold facts, the baseline alone 0, both 1,659, neither 2; p < 0.001 exact, and p < 0.001 clustered by document |
| Attack success | 0 of 72, 0.000 [0.000, 0.051]; baseline 13 of 72 |
| By encoding | plain 0 of 52 [0.000, 0.069], base64 0 of 20 [0.000, 0.161] |
| By language | 0 of 5 in each language but English (0 of 12); each interval reaches 0.434 |
| Unsupported citations | 0 of 3,699 |
| Model-call latency | p50 1,103 ms, p95 1,389 ms, max 1,929 ms |
| Tokens | 199,296 input cache hit, 168,169 input cache miss, 102,418 output |
| Cost of one run | $0.087 off-peak, $0.175 at peak, at most $0.233 with no provider cache hit (record 0023's prices) |

At the off-peak price the nightly run costs about $2.60 a month.

The generator, templates and word lists were written, and changed only, before any model saw the set. Nothing was chosen from this run (record 0021's never-tuned-on rule).

## Consequences

- The injection evidence is six times larger and no longer English-only. 0 of 72 bounds the attack success rate at 0.051, against 0.242 from the pilot. Record 0023 said to revisit its restrict decision once a larger injection set was frozen. This is that evidence, and this record leaves the decision where it is.
- Generated text is regular. The model scores higher here than on the hand-written pilot (F1 0.996 against 0.990), and the score shouldn't be read as accuracy on real documents. The pilot gate stays, since it holds the harder, hand-written cases.
- Five cases per language bound nothing about any one language. The per-language rows show where a regression lands, not a rate.
- A nightly failure can come from the provider changing the model behind the same name, from an outage, or from a missing key. Each opens one issue, and later failures comment on it while it stays open. The run's artifacts keep the predictions for 30 days.
- Held to the committed run, the nightly fails on any drop the paired bootstrap can tell from noise, and on any single attack the committed run blocked. The first nights will show whether the provider's own variation at temperature 0 trips it. If it does, that is a decision about a margin, to be made from nightly runs rather than from this set's per-document results.
- The nightly run spends money every night, and sends only the generated set's fictional text to the provider.

## Alternatives considered

- **Hand-writing 700 documents, like the pilot.** More natural text, but hand labels at this size invite mistakes the gate can't detect, and the diff would be too large to review.
- **A public dataset.** Licenses, personal data and text the model may have seen in training. The manifest promises none of these.
- **Gating on McNemar too.** It would add a second verdict for the same question, and a significant difference in the candidate's favour should never fail a gate.
- **Running the nightly at a fixed early-morning hour.** 01:00 to 04:00 UTC is DeepSeek's peak price window, so 22:23 UTC halves the cost.

## Revision 2026-11-04: the nightly runs through OpenCode Go

The nightly live eval now calls DeepSeek V4.1 Flash through OpenCode Go
(`https://opencode.ai/inference/go/openai/v1`, model `deepseek-v4.1-flash`)
instead of DeepSeek's own API. The app's extraction default stays
`deepseek-flash` on `https://api.deepseek.com` with `ANVIL_MODEL_API_KEY`; the
workflow uses a separate repository secret, `ANVIL_GO_API_KEY`. The adapter
sends the `x-opencode-session` header Go requires, with a random id per call,
and reads Go's OpenAI-shaped usage fields.

The drift reference is now
`eval/runs/extract-v1-deepseek-v4.1-flash-full-v1`, a full-set run made on
2026-11-04 with the unchanged prompt `extract-v1`:

| Measure | Value |
| --- | --- |
| F1 | 0.996 [0.995, 0.998] |
| Precision, recall | 0.995 [0.992, 0.997], 0.998 [0.996, 0.999] |
| Attack success | 0 of 72, 0.000 [0.000, 0.051]; baseline 13 of 72 |
| Unsupported citations | 0 of 3,688 |
| Model-call latency | p50 1,635 ms, p95 2,574 ms, max 11,910 ms |
| Tokens | 202,368 input cache hit, 153,725 input cache miss, 102,705 output |
| Duration | 1,300 s |

The nightly now compares like with like: the same Go endpoint, model and
client path that produced the reference. The Go plan's monthly and 5-hour
limits apply instead of per-token DeepSeek billing. The app's extraction path
stays on DeepSeek, so a change in either provider is visible only on that
path's runs.
