-- Center: branch config, notifications, MP integration (sect 8.4).

-- ---------------------------------------------------------------- config (sect 4.8)
ALTER TABLE branches
    ADD COLUMN config_published_version int,
    ADD COLUMN config_sent_at           timestamptz,   -- last (re)publish attempt, drives the resend reconciler
    ADD COLUMN config_applied_version   int,
    ADD COLUMN config_applied_at        timestamptz;

-- One version number for the whole network; above the node file default (4).
CREATE SEQUENCE config_version_seq START WITH 100;

CREATE TABLE config_global (
    id         smallint PRIMARY KEY CHECK (id = 1),
    queue      jsonb NOT NULL DEFAULT '{}',
    bounds     jsonb NOT NULL DEFAULT '{}',
    planning   jsonb NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text
);
INSERT INTO config_global(id, bounds) VALUES (1, '{
  "slack_min": {"min": 0, "max": 15},
  "call_timeout_sec": {"min": 30, "max": 300},
  "max_recalls": {"min": 0, "max": 5},
  "idle_alert_min": {"min": 5, "max": 60},
  "long_service_min": {"min": 5, "max": 60}
}');

-- NULL section = inherit global; bounds replace the global ones as a whole, queue/planning are deep-merged.
CREATE TABLE config_branch (
    branch_id  text PRIMARY KEY REFERENCES branches(id),
    queue      jsonb,
    bounds     jsonb,
    planning   jsonb,
    updated_at timestamptz NOT NULL,
    updated_by text
);

CREATE TABLE config_versions (
    version    int  NOT NULL,
    branch_id  text NOT NULL REFERENCES branches(id),
    message    jsonb NOT NULL,          -- assembled message without password hashes
    author     text,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (version, branch_id)
);
CREATE INDEX ix_config_versions_branch ON config_versions (branch_id, version DESC);

CREATE TABLE branch_employees (
    id         uuid PRIMARY KEY,
    branch_id  text NOT NULL REFERENCES branches(id),
    full_name  text NOT NULL,
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL
);

CREATE TABLE branch_users (
    id            uuid PRIMARY KEY,
    branch_id     text NOT NULL REFERENCES branches(id),
    login         text NOT NULL,
    password_hash text NOT NULL,        -- bcrypt
    role          text NOT NULL CHECK (role IN ('OPERATOR', 'BRANCH_ADMIN')),
    employee_id   uuid REFERENCES branch_employees(id),
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    UNIQUE (branch_id, login)
);

-- ---------------------------------------------------------------- notifications (sect 4.7, 11.3)
CREATE TABLE notification_log (
    id         bigserial PRIMARY KEY,
    dedup_key  text NOT NULL,
    event_id   uuid,
    branch_id  text NOT NULL,
    result     text NOT NULL CHECK (result IN ('sent', 'failed', 'expired', 'rejected')),
    error      text,
    created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_notification_sent ON notification_log (dedup_key) WHERE result = 'sent';
CREATE INDEX ix_notification_log_time ON notification_log (created_at);

-- ---------------------------------------------------------------- MP (sect 4.6)
CREATE TABLE mp_requests (
    mp_appointment_id text PRIMARY KEY,
    branch_id         text NOT NULL,
    body_hash         text NOT NULL,
    body              jsonb NOT NULL,
    status            text NOT NULL CHECK (status IN ('accepted', 'confirmed', 'rejected', 'callback_sent', 'callback_failed')),
    published         boolean NOT NULL DEFAULT false,   -- PubAck received from MP_INBOX
    display_number    text,
    reason            text,
    callback_attempts int NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);
CREATE INDEX ix_mp_requests_time ON mp_requests (created_at DESC);

-- ---------------------------------------------------------------- DLQ
CREATE TABLE dead_letters (
    id          bigserial PRIMARY KEY,
    kind        text NOT NULL CHECK (kind IN ('notification', 'mp_callback')),
    key         text NOT NULL,          -- dedup_key or mp_appointment_id
    branch_id   text NOT NULL,
    payload     jsonb NOT NULL,         -- original event envelope
    attempts    int NOT NULL,
    reason      text NOT NULL CHECK (reason IN ('exhausted', 'expired', 'rejected')),
    last_error  text,
    created_at  timestamptz NOT NULL,
    resolved_at timestamptz
);
CREATE INDEX ix_dead_letters_open ON dead_letters (kind, created_at DESC) WHERE resolved_at IS NULL;
