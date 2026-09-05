# Build context is the REPO ROOT (see docker-compose.yml): the image bundles the
# current Dock software as a release tarball, which the server auto-publishes at
# startup — every cloud deploy ships the newest Dock version, no manual uploads.
FROM node:22-bookworm-slim AS build
WORKDIR /app
# better-sqlite3 compiles from source if no prebuilt binary matches
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
COPY cloud/package*.json ./
RUN npm ci
COPY cloud/tsconfig.json ./
COPY cloud/src ./src
RUN npm run build && npm prune --omit=dev

# the bundled Dock release: dock/ + the printer icon, version from the package
FROM node:22-bookworm-slim AS dockrel
WORKDIR /rel
COPY dock ./dock
COPY brand/logo/export/app-icon/ios/AppIcon-1024.png ./brand/logo/export/app-icon/ios/AppIcon-1024.png
RUN node -e "const m=require('fs').readFileSync('dock/sidecar/repaper_dock/__init__.py','utf8').match(/__version__ = \"([^\"]+)\"/); require('fs').writeFileSync('/rel/VERSION', m[1])" \
  && tar czf /rel/dock-release.tar.gz dock brand \
  && cat /rel/VERSION

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY cloud/package.json ./
COPY cloud/ui ./ui
COPY cloud/assets ./assets
COPY --from=dockrel /rel/dock-release.tar.gz /rel/VERSION ./bundled/
COPY dock/RELEASE_NOTES.md ./bundled/RELEASE_NOTES.md
VOLUME /app/data
EXPOSE 3000
CMD ["node", "dist/server.js"]
