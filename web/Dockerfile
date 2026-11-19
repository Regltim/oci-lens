ARG OCI_LENS_NODE_IMAGE=node:22.19.0-alpine
ARG OCI_LENS_CADDY_IMAGE=caddy:2.10.2-alpine
FROM ${OCI_LENS_NODE_IMAGE} AS builder
ARG OCI_LENS_NPM_REGISTRY=https://registry.npmjs.org
ENV COREPACK_NPM_REGISTRY=${OCI_LENS_NPM_REGISTRY}
WORKDIR /workspace/web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --registry="${OCI_LENS_NPM_REGISTRY}"
COPY web/ ./
COPY scripts/oci-cloud-shell-setup.sh /workspace/scripts/oci-cloud-shell-setup.sh
RUN pnpm build

FROM ${OCI_LENS_CADDY_IMAGE}
COPY web/Caddyfile /etc/caddy/Caddyfile
COPY --from=builder /workspace/web/dist /srv
EXPOSE 8080
