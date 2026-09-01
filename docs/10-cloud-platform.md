# 10 · RePaper Cloud — devices, licensing, fleet

Every RePaper printer — a Dock or a phone running Go — is a device that can be claimed into an account. The cloud is where devices are licensed, watched and updated, and where money changes hands. It shares a domain and a design system with the website.

## Principles

1. **Printing never depends on the cloud.** A Dock prints with the internet down. Entitlements are cached on the device with a grace period (e.g. 30 days) before features degrade; the base function — print to a sheet — never switches off.
2. **Outbound only.** Devices open one TLS connection to the cloud (MQTT or WebSocket); no inbound ports, proxy-friendly, works behind corporate firewalls. Optional fully on-prem mode for enterprises later.
3. **Job content stays local** unless a customer explicitly turns on job archiving. The cloud sees metadata (counts, sizes, timings, errors), not pages.
4. **One identity per device**: a key pair generated on first boot (Dock) or app install (Go), a claim code shown as a QR; later a secure element on our own PCB.
5. **Same brand everywhere**: the console is built on `brand/tokens.css`; Carbon mode by default.

## What it does

| Area | v1 (pilot) | Later |
|---|---|---|
| **Accounts** | Org → sites → users; passkeys + email; roles admin / operator | SSO (SAML/OIDC), audit log |
| **Claiming** | Scan the QR on the Dock's setup page or the app → device joins the org | Bulk claim by serial list, zero-touch for pre-provisioned fleets |
| **Fleet view** | Every device: online/offline, last seen, firmware, IP, printer name, jobs today, errors; sheets known to it with battery/last update | Floor plans, alerts (offline > 10 min, battery low), reports |
| **Config** | Rename, default sheet size, timeout policy, LED brightness, Wi-Fi (via device), cloud on/off | Config templates per site, staged rollout |
| **Updates** | Signed OTA for Dock firmware and the sheet AP; release channels (stable/beta) | Scheduled maintenance windows |
| **Licensing** | Plan per org; entitlements pushed to devices; grace period | Usage-based add-ons, reseller/partner accounts |
| **Billing** | Stripe: subscriptions, invoices, VAT | Purchase orders for enterprise |
| **Support** | Device diagnostics bundle on request, remote log tail | Remote "identify" (blink the ring), remote reboot. Never remote printing to sheets — the printer-to-sheet link stays physical |
| **Website** | Marketing site + login → console | Public status page, docs, partner portal |

## Monetisation — a first model

Keep it simple to start; make it explainable in one sentence per product.
Refined Sep 2026 into a three-tier sketch (draft numbers, shown as a preview on the console's Billing page):

- **Free — €0 forever**: one device per account, up to 10 sheets, unlimited prints, fleet view & claiming, manual updates, personal workspace. The honest entry that keeps the "it's just a printer" promise.
- **Team — ~€4 / device / month**: unlimited devices and sheets (devices priced each, sheets never — BLE range meters big fleets into more Docks by itself); organisations (members, roles, invitations), sites, alerts with email digest, automatic signed updates, read API keys.
- **Pro — ~€9 / device / month**: everything in Team plus write API & webhooks, staged rollouts & maintenance windows, 12-month history, remote diagnostics & priority support, org branding in the console.
- **Enterprise**: a conversation — on-prem, SSO, purchase orders, SLAs.
- **Sheets & accessories**: hardware margin, sold in the console's Shop.
- **Go app**: free tier maps to Free (the phone is the one device); paid tiers per device like Docks.

Things deliberately *not* metered, in any tier, ever: per-page or per-sheet fees — they fight the "it's just a printer" promise and the reuse story.

## Architecture

```
 website + console (one app, one domain)        api               device channel
 repaper.io  ──── /login → console ────────  REST/JSON  ──────  MQTT over TLS (or WSS)
 marketing pages · docs · pricing            Postgres           per-device credentials
 fleet · devices · sheets · billing          Stripe webhooks    topics: status, config, ota, identify
                                             object store       heartbeat every 60 s, LWT → offline
```

- **Web + API**: one TypeScript codebase (server-rendered site + logged-in console + API). Postgres. Stripe. Hosted in the EU (Hetzner or Fly.io) — customer data stays in Europe.
- **Device channel**: an MQTT broker (Mosquitto/EMQX) with per-device certificates or tokens; the Dock's cloud agent and the Go app speak the same small protocol: `hello`, `status`, `config`, `ota`, `identify`, `entitlement`.
- **Data model**: `org`, `site`, `user`, `device` (kind: dock | go), `sheet` (model, size, palette, mac, nfc id, battery), `job` (metadata), `entitlement`, `firmware_release`, `event`.
- **Website in the same repo/app**: the marketing pages use the same tokens and components as the console; "Log in" leads to the fleet view. Later a public status page can show "n Docks online" (aggregated, opt-in) as a live proof point.

## Sequence for a new Dock

1. Boots → generates key pair → shows QR (claim code) on its setup page and blinks blue.
2. User scans QR (logged in on the website) → device appears in the org, named, assigned to a site.
3. Device connects outbound, receives config + entitlement, reports status every minute.
4. Fleet view shows it green. Printing worked from step 1 regardless.

## Build order

1. Accounts + claiming + fleet list + device channel (the heartbeat) — enough for the pilot. **Built Sep 2026: `cloud/` in this repo** (Fastify + SQLite + WebSocket, console in the Dock UI's design language; Dock side: `cloud.py` agent + a Cloud card in Settings).
2. Config push + OTA.
3. Billing + entitlements.
4. Alerts, reports, SSO, on-prem.

## Pilot deployment (decided Sep 2026)

The pilot runs on Rouven's existing Hetzner server, on a `schisch.net` subdomain next to the projects already hosted there. It is a **closed pilot**: console only (no marketing pages), no public signup — accounts are created manually, demos happen with Rouven's own login. `noindex`, but no basic-auth in front (it would break the device channel, which shares the host). Devices learn the cloud URL from config, so moving to the real product domain later is a config push, not a migration.

## Open decisions

- Product domain (`repaper.io` / `.eu` / `.de` — still unchecked) and whether the console lives at `/console` or `app.` subdomain. The pilot lives on `schisch.net` regardless.
- Pricing numbers (Cloud per Dock per month, Go Pro per user).
- MQTT vs. WebSocket for the device channel. For the pilot: plain WebSocket from the sidecar, but with the message vocabulary above kept intact so the transport can be swapped later (same pattern as SheetTransport). A broker only when the fleet justifies it.
