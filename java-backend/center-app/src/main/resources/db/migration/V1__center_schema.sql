-- Center db (doc, sect 8.4).

-- Branch registry: populated automatically upon the first heartbeat.
CREATE TABLE branches (
  id                 text PRIMARY KEY,
  name               text NOT NULL,
  timezone           text NOT NULL,
  first_seen_at      timestamptz NOT NULL,
  last_heartbeat_at  timestamptz NOT NULL,
  last_heartbeat     jsonb NOT NULL,          -- last heartbeat: outbox lag, windows, queue, service catalog(судя по всему, в реальной почте такого нет)
  source_attached    boolean NOT NULL DEFAULT false   -- The BRANCH_EVT branch is connected to CENTER_EVT.
);

-- Partitioning by event occurrence. (нововведение) for the network: PARTITION BY RANGE (occurred_at), hot 30 days.
CREATE TABLE events (
  event_id        uuid PRIMARY KEY,        
  branch_id       text NOT NULL,
  event_type      text NOT NULL,
  aggregate_type  text,
  aggregate_id    uuid,
  occurred_at     timestamptz NOT NULL,
  received_at     timestamptz NOT NULL,
  payload         jsonb NOT NULL
);
CREATE INDEX ix_events_branch_time ON events (branch_id, occurred_at);
CREATE INDEX ix_events_type_time ON events (event_type, occurred_at);
