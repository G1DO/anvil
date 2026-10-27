# Burn evidence — issue #33

Provenance: containerized burn run via `SaturationSignalTest` (Testcontainers
`postgres:18`) plus `/observe/saturation` and `/metrics` exposition checks in
the same test. No prod target exists; local compose (`docker compose up
--build`) is the prod-like staging per `README.md`.

## Thresholds and windows

- `QueueBurn`: `anvil_queue_age_seconds{state="queued"} > 60` for `5m`.
- `ClaimBurn`: `anvil_claim_latency_seconds{op="claim",quantile="0.99"} > 0.5`
  or `anvil_pool_wait_seconds{pool="hikaricp",quantile="0.99"} > 0.5` for `5m`.
- Rules: `prometheus/alerts-33.yml`; same logic in
  `src/main/java/com/g1do/anvil/observe/BurnAlerts.java`.

## Induced burn (firing)

- Seed one `QUEUED` job backdated 10 minutes (`created_at =
  clock_timestamp() - INTERVAL '10 minutes'`); inject 100x 2s claim samples.
- Observed: `queue_age` ~612s, `claim_p99` 2.0s, both alerts `1`.
- Files: `alert-payload-burn.json`, `metrics-snapshot-burn.txt`.

## Green control (silent)

- Fresh `QUEUED` rows, 100x 5ms claim/pool samples.
- Observed: `queue_age` 0.8s, `claim_p99` 0.005s, both alerts `0`.
- Files: `alert-payload-green.json`, `metrics-snapshot-green.txt`.

## Screenshot

`burn-screenshot.svg` renders the same two states (red firing vs green
silent) with thresholds. It is a checked-in rendering of the measured
snapshots above, not a Grafana capture (no dashboard per issue non-goals).

## Tenant safety

All snapshots and payloads were asserted tenant-free in
`SaturationSignalTest#expositionCarriesNoTenantBytes` (secret tenant
`t_secret_xyz_33` absent from `/observe/saturation` and `/metrics`).
