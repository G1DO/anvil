# ANVIL operator console

React 19 + TypeScript 7 single-page app built with Vite 8. It started from the
official Vite React TypeScript template (`npm create vite@latest frontend --
--template react-ts`), with the ANVIL UI, API client and tests layered on top.
It is served at `/console/` by the Spring app; the production bundle is committed
under `src/main/resources/static/console/`.

## Commands

```bash
npm ci                 # install the pinned lockfile
npm run dev            # Vite dev server; proxies /api and /audit to localhost:8080
npm run lint           # oxlint
npm run test           # Vitest + Testing Library
npm run typecheck      # tsc -b
npm run build          # tsc -b && vite build; emits the committed static bundle
```

Run `task frontend:check` from the repository root to do all of the above and
prove the committed bundle is current. CI runs the same task.

## Contract

- The page sends `X-Tenant-Id` on every read and keeps the tenant out of the URL.
- It renders only after the job, events and replay reads all settle; a missing
  job and another tenant's job look the same.
- The build is a single classic deferred script so the HtmlUnit acceptance test
  can drive it. Vite inlines imported CSS into an IIFE bundle, which the strict
  CSP would block, so the stylesheet lives in `public/app.css` and is linked
  from `index.html` instead of being imported from `src/`.
- Do not add `dangerouslySetInnerHTML`, inline scripts, inline styles or
  third-party runtime assets. Chain payloads are rendered as escaped text.
- The Java `OperatorConsoleTest` remains the end-to-end browser test; Vitest
  covers the component and pagination states.
