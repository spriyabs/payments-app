# ---- build stage (Node is build-time only) ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
# Use the lockfile when it exists (commit package-lock.json after the first install)
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY . .
RUN npm run build

# ---- runtime stage: nginx serves the built files ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Writes /config.json and the Content-Security-Policy from PAYMENTS_API_URL at container start.
COPY docker/40-write-config.sh /docker-entrypoint.d/40-write-config.sh
RUN chmod +x /docker-entrypoint.d/40-write-config.sh
EXPOSE 80
