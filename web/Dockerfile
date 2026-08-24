# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /build

# package.json + lockfile primeiro: enquanto as dependencias nao mudarem, o
# Docker reaproveita esta camada e o npm ci nao roda de novo a cada alteracao
# de codigo. npm ci (nao install) instala exatamente o que esta no lockfile.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# tsc -b && vite build -> /build/dist
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.27-alpine AS runtime

# A API nao tem CORS: o nginx serve o dist E faz proxy de /api, deixando front
# e API na mesma origem, igual ao proxy do vite.config.ts em dev.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
