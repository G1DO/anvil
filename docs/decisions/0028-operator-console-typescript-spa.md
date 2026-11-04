# 0028 — Operator console as a TypeScript single-page app

Status: accepted 2026-11-04 (follow-up to #77). Builds on the operator console in
record #77 and keeps its trust boundary: the console has no server-side state, and every
request carries the operator-entered `X-Tenant-Id` exactly as a tenant client does.

## Context

The first operator console was a static HTML page with hand-written CSS and a classic
JavaScript file. It worked, but it had no component or type model, no unit tests, and
the UI was limited to a form and a table. Operators need a maintainable console that can
grow (job search, chain filtering, replay history) without turning into one large DOM
script. The page also has to stay inside a strict content security policy and remain
drivable by the existing HtmlUnit acceptance test.

## Decision

- The console is a **React 19 + TypeScript 7 single-page app** built with **Vite 8** on
  **Node 22.22.1**. `package-lock.json` pins the dependency graph; `npm ci` is the
  install command.
- Source, tests and the lockfile live in `frontend/`. `npm run build` emits the
  production bundle into `src/main/resources/static/console/`, which the Java app
  serves from the classpath. The bundle is committed so the Java build, the Docker
  image and the package do not need Node.
- `task frontend:check` runs `npm ci`, a production-dependency audit, lint,
  unit/component tests, a fresh build, and a `git status` check over the committed
  bundle. CI runs it as its own gate job; the bundle is stale if the checked-in output
  differs from a fresh build.
- The page keeps the original contract: the same routes, `X-Tenant-Id` header, the same
  generic not-found path, and the same DOM IDs the browser test drives. The production
  build is one classic IIFE script loaded with `defer` rather than an ES module, so the
  existing HtmlUnit acceptance test can execute it without a real browser.
- The CSP stays `default-src 'self'`; scripts and styles are external files, there are
  no inline scripts or styles, and React escapes all tenant-controlled text. No CDN,
  external font or image request is used.
- Vitest + Testing Library cover the component states and chain pagination. The Java
  `OperatorConsoleTest` remains the end-to-end browser check against the running app
  and containerized Postgres.

## Consequences

- Working on the console needs Node 22.22.1 and npm; running the service does not.
- A frontend change updates both `frontend/` and the committed static bundle. The
  `frontend:check` gate makes a stale bundle a red build rather than a silent drift.
- The console is one bundle with no code splitting. That is deliberate for a small
  operator page and keeps the CSP and HtmlUnit path simple; revisit if the page grows
  beyond a few hundred kilobytes.
- The UI can use the same design tokens in light and dark schemes. It still has no
  server-side rendering, session, credential or data cache.

## Rejected

- Keeping the hand-written vanilla page: no type checking, no component tests, and it
  already needed manual DOM maintenance for every state.
- Loading React from a CDN: the CSP is same-origin and the console must work in an
  isolated deployment with no third-party network.
- A module-script build: HtmlUnit 5.7, which drives the acceptance test, does not
  execute ES module scripts; the classic deferred IIFE keeps the test real.
- Next.js or SSR: the console is a static operator page; a server-side runtime would add
  a second deployment and a new authority surface without adding tenant data.
