# RePaper Cloud

The fleet console and device channel from `docs/10-cloud-platform.md`, pilot
slice: accounts (seeded, no signup), claiming, fleet view, identify, live
status. Devices — Docks now, Go installs later — connect outbound over one
WebSocket and register themselves; a claim code shown on the device binds it
to an org.

- **Stack**: TypeScript, Fastify, `ws`, better-sqlite3 (Postgres later; every
  query lives in `src/db.ts`), vanilla-JS console in the Dock UI's design
  language on `brand/tokens.css`.
- **Run locally**: `npm install && npm run seed -- --org X --email Y --password Z`
  then `npm run dev` (defaults: port 3000, `data/cloud.db`). Node 22 (`.nvmrc`).
- **Deploy**: see `DEPLOYMENT.md` — Docker + the shared Traefik on the Hetzner box.
- **Protocol** (`/ws/device`, JSON): device sends `hello {id, secret, claim,
  kind, name, version}` (trust on first connect), then `status` heartbeats;
  server pushes `hello_ok`, `claimed`, `identify`. Metadata only, never pages.
