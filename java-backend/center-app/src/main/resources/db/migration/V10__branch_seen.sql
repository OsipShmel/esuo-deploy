-- "Seen at" of a branch (scale design 9.2). Between its content writes a branch behind a regional hub only says "alive"
-- with each heartbeat. Written into branches, that copies the whole row with its heartbeat JSON (~1.8 KB: below the TOAST
-- threshold, so it stays in the row); a narrow table of its own takes ~50 bytes per update, ten times faster at 250 000
-- branches. "Last heard of" = GREATEST(branches.last_heartbeat_at, branch_seen.seen_at) — BranchRegistry.SEEN_AT.
-- No foreign key: a heartbeat of a branch deleted meanwhile must not fail the batch it is in, and readers join from
-- branches, so a row left behind is never read.
CREATE TABLE branch_seen (
  branch_id text PRIMARY KEY,
  seen_at   timestamptz NOT NULL
) WITH (fillfactor = 70);
