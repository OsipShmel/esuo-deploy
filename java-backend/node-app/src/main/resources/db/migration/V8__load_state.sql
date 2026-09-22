-- Load level with hysteresis and the manually chosen day profile (load-planning.md 4.2, 3.1).
--
-- In the database rather than in a field, for the same reason the queue timers keep no in-memory state (S12):
-- the level drives the booking quota, and a restart must not silently reopen an hour the branch had closed.
-- level_since also has to survive a restart — it is what the center's `overload` anomaly counts from, and what
-- the screen shows as "с 09:22".
--
-- One row, like demo_clock: the node serves exactly one branch.
CREATE TABLE load_state (
    id            smallint PRIMARY KEY CHECK (id = 1),
    level         text NOT NULL CHECK (level IN ('normal', 'high', 'overload')),
    level_since   timestamptz NOT NULL,
    -- Незавершённые переходы: с какого момента держится условие подъёма и условие спуска.
    -- NULL означает «переход не идёт», и это не то же самое, что «идёт ноль минут».
    up_since      timestamptz,
    down_since    timestamptz,
    -- Ручной профиль руководителя действует до конца своего дня, поэтому дата хранится рядом:
    -- без неё выбор «сегодня пенсионный день» молча переживал бы полночь.
    manual_profile      text,
    manual_profile_day  date,
    updated_at    timestamptz NOT NULL,
    CHECK ((manual_profile IS NULL) = (manual_profile_day IS NULL))
);
