# Contributing

## Setup

- Java 25 and Docker running. Tests provision Postgres via Testcontainers (`postgres:18`); they fail without Docker. No local Postgres install needed.
- Local run uses Boot Dev Services; `src/main/resources/application.yaml` sets only the app name.

## Commands

```bash
./mvnw test
./mvnw spring-boot:run
```

Targeted suites (same runner, narrower scope):

```bash
./mvnw -B -ntp -Dtest=JobServiceTest test
./mvnw -B -ntp -Dtest='OutboxRelayTest,ContentStoreTest' test
```

Green means the changed behavior is proven against containerized Postgres.

## Workflow

- Short-lived branch → verify → pull request → CI → merge to `main`.
- One coherent reviewable change per PR. The PR explains what changed, why, and how it was verified (including manual evidence and checks intentionally not run). CI owns routine machine-check results.
- Update affected docs in the same PR as the code: architecture index in `docs/architecture.md` plus `docs/architecture/c1.md`, `docs/architecture/c2.md`, and `docs/architecture/slices/<slice>/c3.md` for the container/slice under change, accepted architecturally significant choices as new ADRs in `docs/decisions/` (supersede, never rewrite history), security boundaries in `SECURITY.md`, contracts and timings where they live.
- `main` stays healthy and releasable. CI (`.github/workflows/ci.yml` gate: `lint/types/unit/schema/diagrams/SAST/secrets/build` plus filtered `integration/e2e/contract-check`, `gate` required, same `task` verbs as local) must pass for the exact change.
- Every PR links its tracking issue (`Closes` when it completes the acceptance, `Refs` otherwise); hygiene and audit PRs link or create one — no issue-less PRs.
- Solo work uses documented self-review (changed behavior, failure modes, security/data boundaries, recovery model); request independent review when the change touches authorization, sensitive data, destructive migrations, or delivery/security controls.
