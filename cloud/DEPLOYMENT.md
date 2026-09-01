# RePaper Cloud — Deployment (Hetzner, alongside rally)

Target: `https://repaper.schisch.net` on the existing Hetzner box, reusing the
Traefik instance that already serves rally.schisch.net. Closed pilot: console
only, no signup — accounts are created on the box with the seed script.

## 1. DNS record at IONOS

IONOS panel → Domains → `schisch.net` → DNS:

```
Type: A    Host/Name: repaper    Value: <server IP>    TTL: default
```

Verify: `dig +short A repaper.schisch.net` → the server IP.

## 2. On the server: clone and start

Traefik and the `web` network already run (rally depends on them too):

```bash
ssh <your-server>
docker ps | grep traefik && docker network inspect web >/dev/null && echo ok

git clone git@github.com:rouvenfalkewitz/repaper.git
cd repaper/cloud
cp .env.example .env          # DOMAIN=repaper.schisch.net is already the default
docker compose up -d --build
```

First build compiles better-sqlite3 (a few minutes). Traefik picks the container
up and requests the certificate once DNS is live.

Verify:

```bash
curl -s https://repaper.schisch.net/api/health     # → {"status":"ok"}
docker compose logs -f repaper-cloud
```

## 3. Create the pilot account (no signup exists)

```bash
docker compose exec repaper-cloud node dist/seed.js --org "RePaper" \
  --email you@example.com --password "..." --name "Rouven"
```

Run it again for anyone you want to demo to (same `--org` puts them in the
same fleet).

## 4. Point a Dock at it

On the Dock: **Settings → RePaper Cloud → Cloud address** =
`wss://repaper.schisch.net/ws/device`. Its claim code appears in the same card;
enter it in the console under **Claim a device**. Printing never depends on any
of this — an unreachable cloud only means the fleet view goes stale.

## Updating after changes

```bash
cd repaper/cloud && git pull && docker compose up -d --build
```

## Data & backup

Everything lives in the SQLite file in the `cloud-data` volume:

```bash
docker compose exec repaper-cloud sh -c "cat /app/data/cloud.db" > repaper-cloud-backup-$(date +%F).db
```

## Notes

- The device channel is a WebSocket (`/ws/device`) on the same host — Traefik
  proxies it without extra config. This is also why there is no basic-auth
  middleware in front: it would lock the Docks out.
- The console sends `X-Robots-Tag: noindex` and a deny-all `robots.txt`; there
  are no public pages beyond the login form.
- Job content never reaches the cloud — status heartbeats carry metadata only
  (printer state, sheet battery/temperature, job counts).
