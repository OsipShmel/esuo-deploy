-- БД центра (раздел 8.4). Центр хранит производные данные: истина об очереди — в отделениях.

-- Реестр отделений: заполняется автоматически по первому heartbeat.
CREATE TABLE branches (
  id                 text PRIMARY KEY,
  name               text NOT NULL,
  timezone           text NOT NULL,
  first_seen_at      timestamptz NOT NULL,
  last_heartbeat_at  timestamptz NOT NULL,
  last_heartbeat     jsonb NOT NULL,          -- последний heartbeat: лаг outbox, окна, очередь, каталог услуг
  source_attached    boolean NOT NULL DEFAULT false   -- BRANCH_EVT отделения подключён к CENTER_EVT
);

-- Копия событий отделений. [Предложение] для сети: PARTITION BY RANGE (occurred_at), горячие 30 дней.
CREATE TABLE events (
  event_id        uuid PRIMARY KEY,           -- дедупликация повторной доставки
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
