CREATE TABLE branch (
  id            text PRIMARY KEY,
  name          text NOT NULL,
  address       text,
  timezone      text NOT NULL
);

CREATE TABLE services (
  id                uuid PRIMARY KEY,
  branch_id         text NOT NULL REFERENCES branch(id),
  code              text NOT NULL,
  name              text NOT NULL,
  number_prefix     text NOT NULL,
  slot_duration_min int  NOT NULL DEFAULT 15,
  slot_capacity     int  NOT NULL DEFAULT 1,
  active            boolean NOT NULL DEFAULT true,
  UNIQUE (branch_id, code)
);

CREATE TABLE employees (
  id          uuid PRIMARY KEY,
  branch_id   text NOT NULL REFERENCES branch(id),
  full_name   text NOT NULL,
  active      boolean NOT NULL DEFAULT true
);

CREATE TABLE users (
  id              uuid PRIMARY KEY,
  login           text NOT NULL UNIQUE,
  password_hash   text NOT NULL,
  role            text NOT NULL CHECK (role IN ('OPERATOR','BRANCH_ADMIN')),
  employee_id     uuid REFERENCES employees(id),
  active          boolean NOT NULL DEFAULT true
);

CREATE TABLE windows (
  id                  uuid PRIMARY KEY,
  branch_id           text NOT NULL REFERENCES branch(id),
  name                text NOT NULL,
  status              text NOT NULL CHECK (status IN ('open','closing','closed')),
  current_employee_id uuid REFERENCES employees(id),
  opened_at           timestamptz,
  version             int NOT NULL DEFAULT 0
);

CREATE TABLE window_services (
  window_id   uuid REFERENCES windows(id),
  service_id  uuid REFERENCES services(id),
  PRIMARY KEY (window_id, service_id)
);

CREATE TABLE tickets (
  id                      uuid PRIMARY KEY,
  branch_id               text NOT NULL REFERENCES branch(id),
  public_token            uuid NOT NULL UNIQUE,
  display_number          text NOT NULL,
  service_id              uuid NOT NULL REFERENCES services(id),
  source                  text NOT NULL CHECK (source IN ('appointment','qr','walkin')),
  client_category         text NOT NULL DEFAULT 'default',
  status                  text NOT NULL CHECK (status IN
                            ('booked','waiting','called','serving','done','cancelled','no_show')),
  config_version          int  NOT NULL,
  seq                     bigserial NOT NULL UNIQUE,
  issued_at               timestamptz NOT NULL,
  slot_start              timestamptz,
  slot_end                timestamptz,
  checked_in_at           timestamptz,
  eligible_from           timestamptz,
  due_at                  timestamptz,
  target_window_id        uuid REFERENCES windows(id),
  window_id               uuid REFERENCES windows(id),
  employee_id             uuid REFERENCES employees(id),
  called_at               timestamptz,
  call_count              int NOT NULL DEFAULT 0,
  serving_started_at      timestamptz,
  completed_at            timestamptz,
  postpone_count          int NOT NULL DEFAULT 0,
  device_id               text,
  mp_appointment_id       text UNIQUE,
  notified_approaching_at timestamptz,
  close_reason            text,
  updated_at              timestamptz NOT NULL,
  version                 int NOT NULL DEFAULT 0,
  CHECK (source <> 'appointment' OR slot_start IS NOT NULL),
  CHECK (status NOT IN ('waiting') OR (eligible_from IS NOT NULL AND due_at IS NOT NULL)),
  CHECK (status NOT IN ('called','serving') OR window_id IS NOT NULL)
);

CREATE TABLE ticket_events (
  id           uuid PRIMARY KEY,
  ticket_id    uuid NOT NULL REFERENCES tickets(id),
  event_type   text NOT NULL,
  from_status  text,
  to_status    text,
  actor_type   text NOT NULL,
  actor_id     text,
  payload      jsonb NOT NULL,
  occurred_at  timestamptz NOT NULL
);

CREATE TABLE window_events (
  id           uuid PRIMARY KEY,
  window_id    uuid NOT NULL REFERENCES windows(id),
  event_type   text NOT NULL,
  actor_id     text,
  payload      jsonb NOT NULL,
  occurred_at  timestamptz NOT NULL
);

CREATE TABLE incidents (
  id           uuid PRIMARY KEY,
  window_id    uuid REFERENCES windows(id),
  ticket_id    uuid REFERENCES tickets(id),
  kind         text NOT NULL CHECK (kind IN ('technical','operational')),
  description  text NOT NULL,
  reported_by  uuid REFERENCES employees(id),
  status       text NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved')),
  created_at   timestamptz NOT NULL,
  resolved_at  timestamptz
);

CREATE TABLE ticket_counters (
  branch_id   text NOT NULL,
  service_id  uuid NOT NULL,
  day         date NOT NULL,
  last_value  int  NOT NULL,
  PRIMARY KEY (branch_id, service_id, day)
);

CREATE TABLE idempotency_keys (
  key            text NOT NULL,
  scope          text NOT NULL,
  request_hash   text NOT NULL,
  response_code  int,
  response_body  jsonb,
  created_at     timestamptz NOT NULL,
  PRIMARY KEY (scope, key),
  CHECK ((response_code IS NULL) = (response_body IS NULL))
);

CREATE TABLE outbox (
  id             uuid PRIMARY KEY,
  subject        text NOT NULL,
  payload        jsonb NOT NULL,
  headers        jsonb NOT NULL DEFAULT '{}',
  status         text NOT NULL CHECK (status IN ('pending','sent','failed')),
  attempts       int  NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error     text,
  created_at     timestamptz NOT NULL,
  sent_at        timestamptz
);

CREATE TABLE inbox_processed (
  message_id    text PRIMARY KEY,
  stream        text NOT NULL,
  processed_at  timestamptz NOT NULL
);

CREATE TABLE branch_config (
  id               int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  central_version  int   NOT NULL,
  central_config   jsonb NOT NULL,
  local_overrides  jsonb NOT NULL DEFAULT '{}',
  effective_version int  NOT NULL,
  updated_at       timestamptz NOT NULL
);

CREATE TABLE handling_stats (
  service_id        uuid NOT NULL,
  employee_id       uuid,
  window_id         uuid,
  client_category   text NOT NULL,
  avg_duration_sec  double precision NOT NULL,
  sample_count      int NOT NULL,
  updated_at        timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_handling_stats
  ON handling_stats (service_id, COALESCE(employee_id, '00000000-0000-0000-0000-000000000000'),
                     COALESCE(window_id, '00000000-0000-0000-0000-000000000000'), client_category);


CREATE INDEX ix_tickets_queue
  ON tickets (branch_id, service_id, due_at, seq)
  WHERE status = 'waiting';


CREATE INDEX ix_tickets_booked ON tickets (slot_start) WHERE status = 'booked';


CREATE INDEX ix_tickets_called ON tickets (called_at) WHERE status = 'called';


CREATE INDEX ix_tickets_issued ON tickets (branch_id, issued_at);


CREATE INDEX ix_outbox_pending ON outbox (next_attempt_at) WHERE status = 'pending';

CREATE INDEX ix_ticket_events_ticket ON ticket_events (ticket_id, occurred_at);


CREATE UNIQUE INDEX ux_one_active_ticket_per_window
  ON tickets (window_id) WHERE status IN ('called','serving');


CREATE UNIQUE INDEX ux_one_active_ticket_per_device
  ON tickets (branch_id, device_id)
  WHERE device_id IS NOT NULL AND status IN ('booked','waiting','called','serving');

-- Строка слота сериализует конкурентные бронирования одной услуги.
CREATE TABLE appointment_slots (
  service_id uuid NOT NULL REFERENCES services(id),
  slot_start timestamptz NOT NULL,
  PRIMARY KEY (service_id, slot_start)
);
CREATE INDEX ix_ticket_slot ON tickets (service_id, slot_start)
  WHERE source = 'appointment' AND status NOT IN ('cancelled', 'no_show');

-- Локальный день фиксируется при выдаче и не зависит от часового пояса соединения.
ALTER TABLE tickets ADD COLUMN issued_day date NOT NULL;
ALTER TABLE tickets ADD CONSTRAINT ux_ticket_number UNIQUE (branch_id, service_id, issued_day, display_number);
ALTER TABLE services ADD CONSTRAINT ck_slot_duration CHECK (slot_duration_min > 0 AND slot_duration_min <= 1440);
ALTER TABLE services ADD CONSTRAINT ck_slot_capacity CHECK (slot_capacity > 0);
ALTER TABLE tickets ADD CONSTRAINT ck_ticket_slot CHECK (slot_end IS NULL OR slot_end > slot_start);
ALTER TABLE handling_stats ADD CONSTRAINT ck_handling_stats CHECK (avg_duration_sec > 0 AND sample_count >= 0);
ALTER TABLE window_services ALTER COLUMN window_id SET NOT NULL;
ALTER TABLE window_services ALTER COLUMN service_id SET NOT NULL;
