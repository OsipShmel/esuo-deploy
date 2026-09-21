-- Duration model coefficients from the center config (ml-model.md 4.4), stored as is; read by DurationModel.
--
-- A separate column, like planning in V6, and for the same reason: central_config is parsed into QueuePolicy,
-- and foreign keys in it break that parse. There is no duration_models table on the node — the node does not
-- train, it applies what the center published, and the history of versions is kept in the center.
ALTER TABLE branch_config ADD COLUMN model jsonb NOT NULL DEFAULT '{}';
