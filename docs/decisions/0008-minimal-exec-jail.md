# 0008 — Minimal execution jail with default-deny egress

Status: accepted; its residuals are closed by record 0019 (#73). Builds on 0005 (tenant identity for workspace scoping); independent of 0006 (capability gates) and 0007 (fail-closed reads), integrating where execution calls their re-checks.

## Context

No jail existed; `ProcessBuilder` appeared only in crash-test harnesses (`ContentStoreTest`, `OutboxRelayTest`), not containment. Issue #23 requires an allowed run to still succeed while being unable to read/effect another tenant or exfiltrate via network/redirect, without broker/K8s/Redis/OpenSearch/gRPC/streaming/gVisor in v1.

## Decision

Contain untrusted execution with a minimal process + API jail (`src/main/java/com/g1do/anvil/exec/ExecJail.java`, `JailEgress.java`, `JailResult.java`, `EgressDeniedException.java`):

- Filesystem: per-tenant writable workspace only (`<anvil.exec.root>/<tenant>`, default `var/exec-workspaces`). `resolveInWorkspace` denies absolute paths, `..` segments, `%` (encoded traversal), and null bytes with `SecurityException`, plus anything normalizing outside the workspace and any symlink-traversing path (fail-closed, even inside-pointing links deny). Tenant names follow the CAS escape rules. `readWorkspaceFile(caller, resource, rel)` denies caller-scoped `NoSuchFileException` (no oracle) when `caller != resource`.
- Process: `run(tenant, argv, extraEnv, timeout)` forks a real child with `directory(workspace)`; the environment is cleared first (`env -i` equivalent) leaving only `PATH=/usr/bin:/bin`, `ANVIL_TENANT=<tenant>`, and validated `extraEnv` (`ANVIL_TENANT`/`PATH` forced/ignored; `LD_*`/`DYLD_*`/`PYTHON*`/`PERL*`/`RUBY*`/`NODE_*`/`IFS`/`BASH_ENV`/`ENV`/`ZDOTDIR` denied). Shell interpreters deny, absolute/`..`/`%` operands deny, workspace executables deny, symlink-traversing operands deny; `argv` runs directly with no shell; stdout/stderr cap at 1 MiB per stream (truncated + flag); timeout kills and throws. Default timeout `30s`.
- Egress: default-deny (`checkAllowed` with no allowlist denies everything, including public hosts, without network). Blocked targets always deny even when allowlisted: `localhost`/`*.localhost`, metadata names, IPv4 `127/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `0/8`, IPv6 `::1`/expanded `0:0:0:0:0:0:0:1`/`::`/expanded `0:0:0:0:0:0:0:0`/`fe80::/10`/`fc00::/7`/`ff00::/8`/`::ffff:/96`, non-canonical numerics (single-integer, hex-integer, hex-dotted), non-`http(s)`, userinfo. `newClient()` uses `followRedirects(NEVER)`; the untrusted path must check-then-send with it. Denials carry a generic reason only.

## Consequences

- `ExecJailTest` proves RO-root denial, symlink-escape denial, adversarial-child denial (`cat ../sibling`, `cat /etc/passwd`, `touch ../evil`/`/tmp`, shell/workspace-exec denial), clean env (loader/debug keys denied), capped output, denied egress (plus non-canonical integer/hex/expanded/mapped even when allowlisted), un-followed redirect (loopback 302 stays 302), cross-tenant invisibility (`ls`/`cat` show only own files), and an allowed-tenant control on containerized Postgres with forked children. Prior suites stay green.
- Residual: no mount/user/network namespace (the kernel still permits raw `../sibling` or `/tmp` from arbitrary native code once it runs; interpreter `-c` strings, workspace binaries, and `/proc` bypass the argv allowlist, and symlink check-then-use has TOCTOU — `run` is trusted-caller-only with direct `argv` and validated workspace-relative operands), no DNS resolution (DNS-rebind not re-checked), non-canonical IP encodings deny without decoding, no nftables DROP, no full SsrfMatrix. Documented in `docs/security/threat-model.md` and `SECURITY.md`, not closed.
- Rejected: in-process untrusted execution, inherited parent environment, absolute/`..` file operands on the untrusted path, shell execution with untrusted argv, workspace executables, symlink-traversing paths, loader/debug env keys, uncapped output capture, allowlisted-but-blocked egress (including non-canonical/mapped/expanded forms), following redirects, allowlist overriding blocked targets.
