# --- build stage -------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json ./
# package.json only, not package-lock.json: the lockfile is generated on macOS/arm64 and pins
# platform-specific optional native binaries (@oxc-parser/binding-*, part of the Angular/Vite
# toolchain) for that platform. Replaying it against Linux inside this image trips an npm
# dependency-resolver bug (arborist chokes on a stale platform-specific optional entry) - a
# known class of issue with native-binary optional deps in Docker. Resolving fresh here avoids
# it entirely, at the cost of not being byte-for-byte reproducible with local installs.
RUN npm install

COPY . .
RUN npx ng build

# --- runtime stage -------------------------------------------------------------
# The UI is a separate Deployment from the store (see k8s/ui-deployment.yaml), served by nginx
# rather than Spring Boot static resources, specifically so UI replica count is decoupled from
# SHARD_COUNT - scaling the UI has nothing to do with scaling the store.
#
# nginx-unprivileged, not the standard nginx image: the standard image's master process starts
# as root (to bind port 80) even though workers drop to user "nginx". This variant runs
# non-root end to end by listening on 8080 instead - matching the backend container's
# non-root user rather than just approximating it.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

ENV API_UPSTREAM=kv-store-0:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/dist/client/browser /usr/share/nginx/html

EXPOSE 8080
