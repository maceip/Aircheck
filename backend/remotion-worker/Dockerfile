FROM node:24-bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium \
    ffmpeg \
    fonts-liberation \
    ca-certificates \
  && rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV REMOTION_CHROME_EXECUTABLE=/usr/bin/chromium
ENV PORT=8444
ENV REMOTION_WORKER_PORT=8444
ENV HOST=0.0.0.0

WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci --ignore-scripts
COPY src ./src
COPY public ./public
RUN npx tsc && npm prune --omit=dev

# Stateless Cloud Run / local Docker worker
EXPOSE 8444
CMD ["node", "dist/server.js"]
