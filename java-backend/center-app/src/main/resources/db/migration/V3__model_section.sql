-- Duration model coefficients as a config section (ml-model.md 4.4).
--
-- The model is trained offline and published like any other configuration, so it needs no table of its own:
-- the global section holds the network default, the branch section holds that branch's own parameters
-- (including {"enabled": false} to switch the model off for one branch), and config_versions already keeps
-- the history of what was published when.
--
-- NULL in config_branch.model means "inherit the global section", exactly like queue and planning.

ALTER TABLE config_global ADD COLUMN model jsonb NOT NULL DEFAULT '{}';
ALTER TABLE config_branch ADD COLUMN model jsonb;
