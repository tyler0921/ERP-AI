# --- deps --------------------------------------------------------------------
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- build -------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# --- runtime (standalone) ----------------------------------------------------
FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S ledgerguard && adduser -S ledgerguard -G ledgerguard
COPY --from=build --chown=ledgerguard:ledgerguard /app/.next/standalone ./
COPY --from=build --chown=ledgerguard:ledgerguard /app/.next/static ./.next/static
USER ledgerguard
EXPOSE 3000
CMD ["node", "server.js"]
