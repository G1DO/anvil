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
- Update affected docs in the same PR as the code: architecture in `docs/architecture.md`, accepted architecturally significant choices as new ADRs in `docs/decisions/` (supersede, never rewrite history), security boundaries in `SECURITY.md`, contracts and timings where they live.
- `main` stays healthy and releasable. CI (`.github/workflows/ci.yml`: `./mvnw -B -ntp test` on Java 25) must pass for the exact change.
- Solo work uses documented self-review (changed behavior, failure modes, security/data boundaries, recovery model); request independent review when the change touches authorization, sensitive data, destructive migrations, or delivery/security controls.
