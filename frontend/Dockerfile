# syntax=docker/dockerfile:1
# ---- build stage: type-check and bundle the React app ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Empty = same origin: the nginx below proxies /api and /ws to the backend. Set it to a full backend URL
# (e.g. https://api.example.com) only when the frontend is hosted separately from the backend.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- runtime stage: nginx serves the SPA and reverse-proxies /api and /ws ----
FROM nginx:1.29-alpine
# Files in /etc/nginx/templates/*.template are rendered with envsubst into /etc/nginx/conf.d/ on start.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Only these variables are substituted, so nginx's own $variables in the template stay untouched.
ENV NGINX_ENVSUBST_FILTER="BACKEND_" \
    BACKEND_ADDRESS="backend:8080"
EXPOSE 80
