-- Every heartbeat updates last_heartbeat_at of its branch (scale design 9.2): room on each page keeps those updates
-- HOT (no index change, no new page). The heartbeat JSON lives in TOAST and is untouched by such an update.
ALTER TABLE branches SET (fillfactor = 70);
