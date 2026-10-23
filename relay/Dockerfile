# Container image for the existing TermiNow MCP relay. The relay has no third-party dependencies,
# so this just runs the source on a small Node base. Build: docker build -t terminow-relay .
FROM node:20-alpine

WORKDIR /app
# Only the source and manifest are needed (no node_modules to install).
COPY package.json ./
COPY src ./src
COPY test ./test

ENV RELAY_HOST=0.0.0.0
ENV RELAY_PORT=8787
EXPOSE 8787

# Drop to the unprivileged user the base image ships.
USER node

CMD ["node", "src/server.js"]
