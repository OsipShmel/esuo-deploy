-- Metrics history of branches, typed and partitioned by hour (scale design 5.1). Replaces the jsonb rows of V5: a
-- network of 40 000+ branches writes thousands of rows a second, the rules aggregate them in SQL, and old hours are
-- dropped as whole partitions instead of deleting millions of rows. The history (48 h of monitoring) starts anew.
-- Column names and types follow honey.road.center.monitoring.MetricColumns.
DROP TABLE branch_metrics;

CREATE TABLE branch_metrics (
  branch_id        text        NOT NULL,
  ts               timestamptz NOT NULL,   -- receipt by the center
  -- sys block of the node (design 6.1); NULL: not sent
  uptime_sec       bigint,
  cpu_process      real,
  cpu_system       real,
  load_avg         real,
  cpu_count        integer,
  heap_used_mb     integer,
  heap_max_mb      integer,
  gc_pause_ms      integer,
  db_pool_active   integer,
  db_pool_pending  integer,
  db_pool_max      integer,
  db_size_mb       integer,
  disk_free_mb     integer,
  disk_total_mb    integer,
  http_requests    bigint,
  http_rps         real,
  http_p95_ms      real,
  http_5xx_rate    real,
  sse_clients      integer,
  leaf_connected   boolean,
  leaf_rtt_ms      real,
  nats_reconnects  integer,
  log_errors       integer,
  log_warns        integer,
  -- queue part of the heartbeat
  waiting          integer,
  overdue          integer,
  oldest_wait_sec  integer,
  windows_open     integer,
  outbox_pending   integer,
  outbox_lag_sec   integer,
  load_level       text,
  clear_time_min   real,
  -- computed by the center
  started_at       timestamptz,            -- process start by the node's clock: restarts, counter resets
  clock_skew_ms    integer,                -- ts − sys.sent_at_system
  PRIMARY KEY (branch_id, ts)
) PARTITION BY RANGE (ts);

-- Rows arrive in time order: a block range index is hundreds of times smaller than a btree and serves the windows
-- of the rules and of Grafana.
CREATE INDEX ix_branch_metrics_ts ON branch_metrics USING brin (ts);

-- Catches rows of an hour without its partition (a clock jump, maintenance not run yet): an insert never fails for a
-- missing partition. MetricsPartitions moves such rows into the hour's partition when it creates one.
CREATE TABLE branch_metrics_default PARTITION OF branch_metrics DEFAULT;
