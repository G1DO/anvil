# 0017 — Apache-2.0 license

Status: accepted 2026-10-29 (#80).

## Context

The repository is public but had no license. Without one, nobody could legally use, copy or modify the code, or contribute to it. The milestone "Teams can adopt ANVIL under a clear license" needs a license that teams can read and adopt without asking for more permission.

## Decision

- License the project under the Apache License, Version 2.0. The full text is in `LICENSE` at the repository root, and `pom.xml` declares it in a `<licenses>` block.
- Contributions are under the same license unless a contributor states otherwise (Apache-2.0, section 5).
- Apache-2.0 gives every user an express patent license from contributors and ends it for anyone who sues over a patent in the work. It doesn't require derivative works to be open source, so a team can build on ANVIL inside an internal or commercial product.

Alternatives considered:
- **MIT.** Shorter, but no express patent grant.
- **AGPL-3.0.** Copyleft, including over the network: anyone who runs a modified ANVIL as a service must publish those changes. Teams that can't share their modifications would avoid it.

## Consequences

- Changing the license later needs the consent of every contributor, so this choice is hard to reverse.
- `LicenseTest` checks that the LICENSE text and the `pom.xml` license agree. The GitHub repository sidebar reads the license from the `LICENSE` file.
