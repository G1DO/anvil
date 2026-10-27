# Security

## Reporting a vulnerability

Report privately through GitHub private vulnerability reporting: the repository's **Security** tab → **Report a vulnerability**. Don't open a public issue. Include the affected commit, steps to reproduce, and the impact.

There are no releases yet (`0.0.1-SNAPSHOT`); `main` is the supported version.

## Handling

1. Acknowledge within 2 working days.
2. Fix in a private security advisory. Advisory forks skip CI and rulesets, so run `task verify` locally before merging the fix.
3. Publish the advisory together with the fixed version. An unfixed vulnerability is never public.

## Secrets

The repository holds no secret values. `.env.example` lists the names the local stack reads; real values come from your environment or an untracked `.env`. The gate scans the full git history for secrets (`task check:secrets`).

## Threat model

`docs/threat-model.md`.
