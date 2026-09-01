import Fastify from "fastify";
import cookie from "@fastify/cookie";
import websocket from "@fastify/websocket";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { registerApi } from "./api.js";
import { handleDeviceSocket } from "./devices.js";
import { userFromRequest } from "./auth.js";

const UI = join(dirname(fileURLToPath(import.meta.url)), "..", "ui");

const app = Fastify({ trustProxy: true, logger: { level: process.env.LOG_LEVEL || "info" } });
await app.register(cookie);
await app.register(websocket, { options: { maxPayload: 1 << 20 } });

registerApi(app);

app.register(async (f) => {
  f.get("/ws/device", { websocket: true }, (socket, req) => handleDeviceSocket(socket, req.ip));
});

/* The console is a handful of tiny files — served by hand, no-store, like the Dock's UI. */
const page = (name: string) => readFileSync(join(UI, name));
const STATIC: Record<string, [string, string]> = {
  "/tokens.css": ["tokens.css", "text/css"],
  "/app.css": ["app.css", "text/css"],
  "/app.js": ["app.js", "text/javascript; charset=utf-8"],
  "/favicon.svg": ["favicon.svg", "image/svg+xml"],
};

/* signed-in pages: one route each, same shell */
const APP_PAGES: Record<string, string> = {
  "/": "index.html", "/org": "org.html", "/account": "account.html",
  "/billing": "billing.html", "/shop": "shop.html",
  "/sheets": "sheets.html", "/activity": "activity.html", "/updates": "updates.html",
};
app.get("/team", (_req, reply) => reply.redirect("/org"));   // team lives inside the organisation now
for (const [path, file] of Object.entries(APP_PAGES)) {
  app.get(path, (req, reply) => {
    if (!userFromRequest(req)) return reply.redirect("/login");
    reply.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").type("text/html; charset=utf-8").send(page(file));
  });
}
app.get("/login", (req, reply) => {
  if (userFromRequest(req)) return reply.redirect("/");
  reply.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").type("text/html; charset=utf-8").send(page("login.html"));
});
app.get("/join/:token", (_req, reply) => {
  reply.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").type("text/html; charset=utf-8").send(page("join.html"));
});
app.get("/register", (req, reply) => {
  if (userFromRequest(req)) return reply.redirect("/");
  reply.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").type("text/html; charset=utf-8").send(page("register.html"));
});
for (const p of ["/reset", "/reset/:token"]) {
  app.get(p, (_req, reply) =>
    reply.header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").type("text/html; charset=utf-8").send(page("reset.html")));
}
app.get("/robots.txt", (_req, reply) => reply.type("text/plain").send("User-agent: *\nDisallow: /\n"));
/* the one public page: aggregate numbers, no sign-in, no org detail */
app.get("/status", (_req, reply) =>
  reply.header("Cache-Control", "no-store").type("text/html; charset=utf-8").send(page("status.html")));
for (const [path, [file, type]] of Object.entries(STATIC)) {
  app.get(path, (_req, reply) => reply.header("Cache-Control", "no-store").type(type).send(page(file)));
}

const port = Number(process.env.PORT || 3000);
await app.listen({ port, host: "0.0.0.0" });
