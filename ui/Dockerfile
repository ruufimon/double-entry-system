# Builds the Angular UI and serves it with nginx, proxying /api to the banking API.

FROM node:24-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
# Base URL of the banking API, without a trailing slash.
ENV API_URL=http://api:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/banking-ui/browser /usr/share/nginx/html
EXPOSE 8080
