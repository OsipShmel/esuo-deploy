-- Diagnostics snapshots of branches (design 7): taken on request over RPC diag.snapshot, kept 30 days.
CREATE TABLE branch_diagnostics (
  id            uuid        PRIMARY KEY,
  branch_id     text        NOT NULL,
  taken_at      timestamptz NOT NULL,   -- by the center clock
  requested_by  text        NOT NULL,   -- login of the network admin
  size_bytes    integer     NOT NULL,
  payload       jsonb       NOT NULL
);
CREATE INDEX ix_branch_diagnostics_branch ON branch_diagnostics (branch_id, taken_at DESC);
CREATE INDEX ix_branch_diagnostics_taken ON branch_diagnostics (taken_at);
