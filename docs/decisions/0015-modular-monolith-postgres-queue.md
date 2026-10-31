# 0015 — Modular monolith with Postgres as the queue

Status: accepted. This writes down the shape the project has had since its first slice; it wasn't recorded here until 2026-10-27.

## Context

ANVIL needs an API, background workers that claim and commit jobs, an outbox relay, a content store, and an execution jail. Each can be a separate service with its own infrastructure, or a module of one deployable sharing one database. The project's planning notes describe the system as "modular monolith + workers + Postgres + CAS". Earlier records rely on that shape:

- **0001:** one `jobs` store in Postgres for all states.
- **0002:** claims use `SKIP LOCKED` polling on the database clock.
- **0004:** a transactional outbox, so there's no distributed transaction with a broker.
- **0008:** the v1 jail runs without a broker, Kubernetes, Redis or a separate search service.

Alternatives: not recorded at the time.

## Decision

- **One deployable.** A single Spring Boot application (`src/main/java/com/g1do/anvil`) holds the API, the workers, the outbox relay, the content store and the exec jail as packages.
- **PostgreSQL is the source of truth and the queue.** Workers poll it with `SELECT ... FOR UPDATE SKIP LOCKED`. Events leave through the transactional outbox. There's no message broker.
- **Content bytes** live on a filesystem path per tenant, advertised by `artifacts` rows.
- **Deployment** is one app container plus one Postgres container (`docker-compose.yml`, `docs/architecture/deployment.mmd`).

## Consequences

- There's one thing to build, deploy and roll back, and no broker to run. Isolation, fencing and audit rely on Postgres guarantees (row-level security, transactions, triggers) in one place.
- Queue throughput and fairness are bounded by Postgres and solved in SQL. Fair claiming across tenants is record 0020.
- The content store is a local filesystem, so running more than one app instance needs shared storage first.
- Splitting a module out later means replacing in-process calls and shared transactions with a network protocol and the outbox. That decision would need its own record.
