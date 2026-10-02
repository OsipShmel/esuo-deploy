-- Metrics history of branches (design 6.3): one row per heartbeat (resolution esuo.center.metrics.resolution),
-- kept 48 h. Written in batches together with the branches upsert; read by the rules and Grafana.
CREATE TABLE branch_metrics (
  branch_id      text        NOT NULL,
  ts             timestamptz NOT NULL,   -- receipt by the center
  sys            jsonb,                  -- NULL: a node without the sys block
  queue          jsonb       NOT NULL,   -- waiting, overdue, oldest_wait_sec, windows_open, outbox_pending, outbox_lag_sec, load_level, clear_time_min
  clock_skew_ms  integer,                -- ts − sys.sent_at_system
  PRIMARY KEY (branch_id, ts)
);
CREATE INDEX ix_branch_metrics_ts ON branch_metrics (ts);
