workspace "ANVIL" "Capability-secure, deterministically replayable execution fabric." {

    model {
        tenantClient = person "Tenant client" "Sends X-Tenant-Id plus Idempotency-Key. Tenant is the sole authority for reads and writes."
        operator = person "Operator" "Runs compose up, probes /healthz plus /readyz, tails JSON logs by correlationId."

        anvil = softwareSystem "ANVIL" "Capability-secure, deterministically replayable execution fabric." {
            api = container "API service" "Auth, validate, canonical CBOR, single-txn submit, direct-ID reads, title-prefix search, audit reads, presigned URLs, health probes." "Spring Boot 4.1, Java 25"
            workers = container "Workers" "Fenced claim and heartbeat, capability-gated commit, relay publish plus sent_at mark, idempotent consumer, lease reconcile." "JobService, OutboxRelay, reconciler"
            jail = container "Exec jail" "Per-tenant workspace only, cleared env, default-deny egress, redirect-never HTTP." "Forked child per tenant"
        }

        postgres = softwareSystem "PostgreSQL 18" "Source of truth, queue, and outbox. Flyway V1-V4, RLS FORCE, anvil_app NOSUPERUSER NOBYPASSRLS."
        casStore = softwareSystem "CAS store" "Filesystem tenants/{tenant}/{sha}. No cross-tenant dedup, hash-verified reads."
        execWorkspaces = softwareSystem "Exec workspaces" "Per-tenant writable directories only."

        tenantClient -> anvil.api "POST /api/documents, reads, audit reads (X-Tenant-Id)"
        operator -> anvil.api "Probes /healthz, /readyz; reads logs"
        anvil.api -> postgres "JDBC; SET LOCAL app.tenant plus read-back; RLS underneath"
        anvil.workers -> postgres "Claim, heartbeat, commit; relay poll; reconcile"
        anvil.api -> casStore "put and getForCaller; torn tails removed, never served"
        anvil.workers -> casStore "putForJob and link; refcount GC"
        anvil.workers -> anvil.jail "run(tenant, argv, env, timeout)"
        anvil.jail -> execWorkspaces "resolveInWorkspace only"
    }

    views {
        systemContext anvil "C1-SystemContext" "Tenants, operator, ANVIL, Postgres, CAS, exec workspaces." {
            include *
            autoLayout
        }

        container anvil "C2-Containers" "API service, workers, exec jail, and their stores." {
            include *
            autoLayout
        }

        styles {
            element "Person" {
                shape Person
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
        }
    }

}
