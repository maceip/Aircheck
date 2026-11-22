FROM node:24-bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium \
    ffmpeg \
    fonts-liberation \
    ca-certificates \
  && rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV REMOTION_CHROME_EXECUTABLE=/usr/bin/chromium
ENV PORT=8080

WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --omit=dev --ignore-scripts
COPY src ./src
RUN npx tsc

# Stateless Cloud Run worker (4 vCPU, scales to 0 when idle = $0/month idle cost)
EXPOSE 8080
CMD ["node", "dist/server.js"]
