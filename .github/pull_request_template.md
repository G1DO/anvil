## What

<!-- One coherent reviewable change. What changed in one paragraph. -->

## Why

<!-- Tracking issue (required, no issue-less PRs). -->
<!-- Closes only work completing the issue acceptance; Refs otherwise. -->

Closes # / Refs # (delete one, fill number):

## Tests

<!-- How verified: commands run, containerized Postgres evidence, manual evidence, checks intentionally not run. CI owns routine machine-check results. -->

- [ ] `task verify` (or scoped subset + reason, listed below)
- Commands run:
- Intentionally not run (with reason):

## Migration / Contract / Rollback (fill only when triggered, delete section otherwise)

- Migration notes (destructive migrations need independent review):
- Contract impact (`contracts/openapi.yaml` changed? compat evidence):
- Rollback ref (delivery PRs: version link; no prod target yet means state N/A with reason):

## Change checklist (all 13 boxes)

- [ ] Change 1 — Linked issue: body carries `Closes #n` (completes acceptance) or `Refs #n`, matching branch key
- [ ] Change 2 — Tests: changed behavior proven against containerized Postgres; evidence above, no unchecked-in skips
- [ ] Change 3 — Docs/decision: `docs/architecture/*.mmd` plus `data-model.md` slice, new ADR in `docs/decisions/` if architecturally significant (supersede, never rewrite), `SECURITY.md` / threat-model / contracts-timings where touched
- [ ] Change 4 — No secrets: no credentials, tokens, or real customer data in diff, logs, or screenshots
- [ ] Change 5 — Purpose: What/Why above state the single purpose; one change per PR
- [ ] Change 6 — Edge cases: failure modes and boundary cases listed or N/A with reason
- [ ] Change 7 — Fit: follows repo conventions (`task` verbs only, no direct push to `main`, `gate` sole required check)
- [ ] Change 8 — Deterministic tests: no time-, order-, or network-dependent assertions; repeat runs pass
- [ ] Change 9 — Security: auth, sensitive-data, and destructive-migration impact stated; independent review requested if touched
- [ ] Change 10 — Operability: build, migration, and recovery behavior stated; delivery record fields only where delivery is claimed
- [ ] Change 11 — Names: branch is `NN-short-slug`, title carries the key and closing word
- [ ] Change 12 — Closing words: `Closes` used only when acceptance is fully met, `Refs` otherwise
- [ ] Change 13 — Threshold where signals claimed: any numeric claim states threshold, sample, and where measured, or N/A
