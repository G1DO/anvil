# 0019 — Kernel-sandboxed exec jail and connect-time egress checks

Status: proposed 2026-10-31 (#73). Closes the residuals record 0008 left open. No production code calls the jail yet.

## Context

Record 0008's jail was safe only for trusted callers. It had no mount, user or network namespace, so once untrusted native code ran, the kernel let it read `../sibling` or `/tmp`. Interpreter `-c` strings, workspace binaries, `/proc` and symlink races got past the argv checks. Egress was denied only in Java. There was no network-layer drop, no DNS-rebind check at connect time, non-canonical IP forms were refused rather than decoded, and denial timing wasn't tested as an existence oracle.

## Decision

**Run every child under bubblewrap (`bwrap`), and treat the kernel, not argv validation, as the boundary.**

- `ExecJail.run` launches `bwrap --unshare-all --unshare-user --unshare-cgroup --die-with-parent --new-session --cap-drop ALL --clearenv`. This gives new user, mount, PID, IPC, UTS, cgroup and network namespaces. `--unshare-all` alone only tries the user and cgroup namespaces, so they're asked for outright: a host that can't create one fails closed.
- The child's file system holds only:
  - `/usr` read-only, plus the `/bin` and `/lib` links, or binds on a host without merged `/usr`;
  - a fresh `/proc` and `/dev`;
  - a private tmpfs `/tmp`;
  - its own workspace, bound read-write at `/work`.
- Sibling workspaces, the exec root, the host `/tmp`, `/etc` and host processes don't exist inside the sandbox.
- **Network-layer drop.** The child's network namespace has only its own loopback and no routes. The kernel drops every packet to another host, including host loopback services such as Postgres, before any filter would see it. We take this as the "nftables or equivalent" the issue asks for. It's stronger than a DROP rule: there's no interface to filter and no rule to get wrong.
- **Egress goes through the parent.** The only way out is `JailEgress` in the parent:
  - It allows only HTTPS. It connects with TLS, SNI and host-name verification, so nothing on the path can read or rewrite what it fetches. Plaintext `http` is denied like any other scheme.
  - It decodes IP literals (POSIX forms such as `0x7f.1`, `2130706433`, `0177.0.0.1`, `127.1`, and IPv6) and checks the address they name. From then on it uses the canonical address, for the `Host` header and the certificate check.
  - It denies private, loopback, link-local, carrier-grade NAT, documentation, benchmarking, multicast and reserved IPv4. For IPv6 it passes only global unicast (`2000::/3`), minus documentation, the `2001::/23` protocol block and 6to4 of a blocked IPv4 address; NAT64 passes only for a public IPv4 address. Allowing a range rather than listing bad ones means a new special-purpose IPv6 block is denied by default.
  - It never resolves DNS for a URL that isn't allowlisted.
  - For an allowlisted name, it resolves again when it connects, denies if any answer is blocked, and connects to the vetted address itself.
  - It denies every 3xx response.
- **Fail closed.** `run` probes the sandbox once. A missing binary, or a kernel or container that refuses unprivileged namespaces, throws `IllegalStateException("sandbox unavailable")`, and nothing runs unconfined.
- **No check-then-use race with the parent.**
  - `run` holds the tenant's lock shared. `readWorkspaceFile` and `writeWorkspaceFile` hold it exclusively.
  - When the child exits, the PID namespace goes with it. On timeout, `run` kills the whole process tree and waits for it before releasing the lock. Either way, when `run` returns no jailed process of that tenant is left to swap a checked path for a symlink.
  - A cross-tenant read is denied before any lock or file access, so a busy or existing sibling can't be timed.
- The argv checks from 0008 stay as defence in depth.

## Consequences

- Each acceptance check is proven by a test:
  - `ExecJailTest.everyNamespaceIsNewAndNoCapabilityIsHeld` compares each namespace inside the jail with the host's and reads a zero effective capability set.
  - `ExecJailTest.nativeCodeCannotReachSiblingWorkspaceOrSharedTmp` runs native code that gets past the argv checks. It shows the same script reads the sibling when unjailed, and reads or writes neither the sibling nor `/tmp` inside the jail.
  - `ExecJailTest.jailEgressDroppedAtNetworkLayer` connects from bash `/dev/tcp`, with no Java involved. It reaches host loopback unjailed. Inside the jail it reaches nothing, and the namespace shows only `lo` and no routes.
  - `ExecJailTest.hostnameRebindDeniedAtConnect` covers a name that rebinds after the allowlist check.
  - `ExecJailTest.productionConnectorIsTlsAndPlaintextIsDenied` shows plaintext is denied before any lookup, and the production connector fails a handshake with a plaintext server.
  - `ExecJailTest.tlsConnectorVerifiesCertificateAndHostName` fetches over real TLS from a loopback server. It shows SNI carries the URL's host, a trusted certificate for another name is refused, and the production connector refuses a certificate outside the JVM's trust store.
  - `ExecJailTest.ssrfMatrixAllDenied` runs 80+ cases, each denied with its own host allowlisted.
  - `ExecJailTest.deniedEgressTimingHidesExistence` and `ExecJailTest.deniedWorkspaceReadTimingHidesExistence` compare the median of 201 interleaved trials for an existing and a missing target. The two must be within **1 ms**. A stub resolver adds 25 ms to any lookup, so an egress denial that resolved would show.
  - `JailContainerEscapeTest` runs the same escape probe inside a container on the production runtime base image. With Docker's default profiles, the jail refuses to run.
- **Hosts need bubblewrap and unprivileged user namespaces.** The gate's `test` job installs `bubblewrap` from the Ubuntu archive. Developers need it too (README). The package isn't pinned to a version: the distro archive drops superseded versions, so a pin would break the build on every security update.
- **Container deployment needs relaxed outer profiles.** Docker's default seccomp and AppArmor profiles block the namespaces bwrap needs. A container that runs the jail needs a seccomp profile that allows `unshare` and `clone` with namespace flags, and an AppArmor profile that allows `mount`, or both unconfined, as the escape test uses. It also needs `systempaths=unconfined`: Docker masks parts of `/proc`, and the kernel won't mount the sandbox's own `/proc` inside a user namespace while the outer one has masked paths. Binding the host's `/proc` instead would show the jail host processes. The production image doesn't install bubblewrap yet, because nothing calls the jail. Both must change before a production caller lands.
- **Residual:**
  - The child has no seccomp filter of its own, so a kernel exploit from inside the sandbox isn't contained.
  - There are no cgroup limits, so a fork bomb or memory exhaustion isn't contained.
  - The tenant lock is per JVM, so two app instances sharing one exec root wouldn't exclude each other.
  - Allowlist matching for names is exact. An attacker who controls an allowlisted name's DNS can point it only at public addresses.
- **Rejected:**
  - nftables rules on the host, which need root and global state and can be misordered; an empty network namespace needs neither.
  - A container per run, which would give the app the Docker socket, root-equivalent on the host.
  - `java.net.http.HttpClient` for egress, which resolves on its own at connect time and can't be pinned to a vetted address.
  - Keeping the argv checks as the boundary.
