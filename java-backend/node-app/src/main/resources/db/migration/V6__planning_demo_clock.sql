-- Load planning section from the center config (load-planning.md 3.1), stored as is; read by LoadPlanner.
ALTER TABLE branch_config ADD COLUMN planning jsonb NOT NULL DEFAULT '{}';

-- DemoClock anchors (load-planning.md 7.1): virtual = anchor_virtual + (real - anchor_real) * rate.
CREATE TABLE demo_clock (
    id             smallint PRIMARY KEY CHECK (id = 1),
    anchor_real    timestamptz NOT NULL,
    anchor_virtual timestamptz NOT NULL,
    rate           double precision NOT NULL CHECK (rate > 0 AND rate <= 1000),
    updated_at     timestamptz NOT NULL
);
