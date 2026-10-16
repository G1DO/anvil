# HTTP contracts

Source: `openapi/openapi.yaml` (version `0.1.0`). Code and tests validate
against it; OpenAPI is the source, never a copy in Notion.

Covered: `POST /api/documents` (aliases `/api/v1/documents`, `/documents`),
`GET /api/documents` (aliases `/api/v1/documents`, `/documents`),
`GET /audit/events` (aliases `/api/audit/events`, `/api/v1/audit/events`),
plus operability `GET /healthz`, `GET /readyz` (no auth).

Versioning: additive-only in v1. Breaking changes bump `info.version`
(e.g. `0.2.0`) with a compatibility check in CI (`scripts/contract_check.py`
+ review of removed/renamed paths and fields). Consumer/provider versions
are recorded in the Delivery record on Verify.

Compatibility: tenant authority is always `X-Tenant-Id` (query `tenant`
params ignored); cross-tenant reads return empty same-as-missing (no oracle);
limits default `20`, max `100`; cursor is tenant- and job-scoped UUID
(foreign/missing resolves to `0` rows).
