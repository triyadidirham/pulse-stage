FROM node:24-alpine
WORKDIR /app
COPY package.json ./
COPY apps/server/src ./apps/server/src
COPY apps/web/public ./apps/web/public
COPY packages/protocol/src ./packages/protocol/src
RUN mkdir -p /app/data && chown node:node /app/data
USER node
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000 DATABASE_PATH=/app/data/pulsestage.sqlite
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s CMD node -e "fetch('http://127.0.0.1:3000/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "apps/server/src/main.mjs"]
