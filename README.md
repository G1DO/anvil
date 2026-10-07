# anvil

Capability-secure, deterministically replayable execution fabric. Current state: scaffold — no endpoints, no migrations.

## Stack

- Java 25 (Temurin LTS), Spring Boot 4.1.1, Maven wrapper
- Runtime: Spring Web (MVC), JDBC, Validation, Flyway, PostgreSQL driver
- Tests: Testcontainers (spins up `postgres:latest`)

## Prerequisites

- Java 25
- Docker running — tests fail without it (Testcontainers provisions Postgres, no local install needed)

## Quickstart

```bash
./mvnw test
./mvnw spring-boot:run
```

The app starts with no endpoints yet; startup-only is expected.

## How the database works today

- `application.yaml` sets only the app name; there is no datasource URL.
- Postgres comes automatically: `@ServiceConnection PostgreSQLContainer` in tests, Boot Dev Services on local run.
- `src/main/resources/db/migration/` is empty, so Flyway is a no-op. The first migration goes there.

## Layout

- `src/main/java/com/g1do/anvil` — application entry
- `src/main/resources` — config + migrations (`static/`, `templates/` are unused Boot defaults)
- `src/test` — `contextLoads` plus the Testcontainers configuration

## Tests

`./mvnw test` boots the full context against containerized Postgres. Green means the stack is proven.

## Explicitly deferred (not created yet)

- License undecided (`pom.xml` carries no license block on purpose)
- No ADRs yet — the first one lands with the Postgres version pin or the first table
- No CONTRIBUTING — single contributor; dev commands live here
- No runbooks or API docs — no deploy target, no endpoints
