-- Active alerts of the monitoring rules (design 6.4): replaced as a whole every evaluation, `since` of a continuing
-- alert is kept. A table, not memory: any center replica can serve the network overview.
CREATE TABLE branch_alerts (
  branch_id   text             NOT NULL,
  rule_id     text             NOT NULL,
  severity    text             NOT NULL,   -- info | warning | critical
  text        text             NOT NULL,
  value       double precision NOT NULL,
  since       timestamptz      NOT NULL,
  updated_at  timestamptz      NOT NULL,
  PRIMARY KEY (branch_id, rule_id)
);
