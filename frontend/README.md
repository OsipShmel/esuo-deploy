# frontend

One multi-page React 18 + TypeScript build (Vite) with every screen of the system. The Docker image of the backend
(`java-backend/Dockerfile`) builds it and embeds the bundle into both `node-app` and `center-app`, which serve it
from the same origin as their API.

| Entry | App | Served by | Users |
|---|---|---|---|
| `client.html` | `src/apps/client` | node `/`, center `/` | Visitors |
| `board.html` | `src/apps/board` | node `/board` | Hall display |
| `operator.html` | `src/apps/operator` | node `/operator` | Operators |
| `admin.html` | `src/apps/admin` | node `/admin` | Branch manager |
| `network.html` | `src/apps/network` | center `/network` | Network operator |
| `demo.html` | `src/apps/demo` | node `/demo` | Demo console |

Shared code is in `src/shared`: API clients, SSE hook, auth gate, formatting, UI components and design tokens.

```bash
npm ci
npm run dev          # Vite dev server on :5173; the services allow it via CORS_ORIGINS
npm run build        # typecheck + production bundle
npm run lint
npm run e2e          # Playwright, phone and desktop viewports, against a running local environment
```
