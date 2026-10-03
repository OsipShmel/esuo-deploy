# Branch log collector (design 5.4): ships WARN, ERROR and lifecycle lines of node-app to BRANCH_LOG on the leaf.
# 0.58.0: the nats sink publishes to JetStream, waits for the server ack and sets Nats-Msg-Id (jetstream.headers.message_id).
# The config is baked in, so a branch PC needs no repository files. Env: BRANCH_ID, NATS_URL.
FROM timberio/vector:0.58.0-alpine
# Since 0.58 ${VAR} in configs is opt-in. Only BRANCH_ID and NATS_URL are interpolated; the config holds no secrets.
ENV VECTOR_DANGEROUSLY_ALLOW_ENV_VAR_INTERPOLATION=true
COPY branch.yaml /etc/vector/vector.yaml
