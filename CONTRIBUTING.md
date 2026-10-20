# Contributing

## Setup

- Java 25 and Docker running. Tests provision Postgres via Testcontainers (`postgres:18`); they fail without Docker. No local Postgres install needed.
- Local run uses Boot Dev Services; `src/main/resources/application.yaml` sets only the app name.

## Commands

Task verbs are the only entry points (same names CI calls):

```bash
task dev
task test
task test:contract
task build
task check:sast
task check:secrets
task check:audit
task check:hygiene
task diagrams
task verify
```

Targeted suites (same runner, narrower scope):

```bash
./mvnw -B -ntp -Dtest=JobServiceTest test
```

Green means the changed behavior is proven against containerized Postgres.

## Commits

- Short imperative subject, link the issue (e.g. `Fix gate skipped-pass (#44)`).
- One coherent reviewable change per commit; no `TODO CI`, `echo placeholder`, or `|| true` stubs.

## Releases

- Through the delivery job with a version and a delivery record (`version|target-proof-links|rollback-ref|recovery-evidence|migration-notes` only where delivery is claimed; N/A absent, never stub).
- No prod target yet: rollback-ref N/A (no prod users), recovery-evidence deferred to O4 restore drill.

## Workflow

- Short-lived branch → verify → pull request → CI → human merge to `main`. No direct pushes to `main` including trivial changes; `gate` is sole required check.
- One coherent reviewable change per PR. The PR explains what changed, why, and how it was verified (including manual evidence and checks intentionally not run). CI owns routine machine-check results.
- Update affected docs in the same PR as the code: `docs/architecture/*.mmd` (C1/C2/C3/detail, one file per maintained view) plus `docs/architecture/data-model.md` for the container/slice under change, accepted architecturally significant choices as new ADRs in `docs/decisions/` (supersede, never rewrite history), security boundaries in `SECURITY.md` and `docs/security/threat-model.md`, contracts and timings where they live.
- `main` stays healthy and releasable. CI (`.github/workflows/ci.yml` gate: `test,sast,secrets-scan,audit,build,arch-validate,hygiene,contract-check` required, `gate` sole required, same `task` verbs as local) must pass for the exact change.
- Every PR links its tracking issue (`Closes` only work completing acceptance, `Refs` otherwise); hygiene and audit PRs link or create one — no issue-less PRs. No `lane:`/`easy`/`medium`/`hard` labels.
- Solo work uses documented self-review (changed behavior, failure modes, security/data boundaries, recovery model); request independent review when the change touches authorization, sensitive data, destructive migrations, or delivery/security controls. Task verbs above are the only entry points.
