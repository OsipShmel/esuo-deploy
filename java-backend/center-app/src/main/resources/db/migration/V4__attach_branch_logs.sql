-- Branch logs (design 5.5): CENTER_LOG gets a BRANCH_LOG source per branch next to the BRANCH_EVT one.
-- source_attached now means "all branch sources are attached"; resetting it makes the 15 s reconciliation
-- (HeartbeatListener.reconcile) add the log source to branches registered before logs existed. Attaching is idempotent.
UPDATE branches SET source_attached = false;
