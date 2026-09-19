-- Границы локального переопределения из центральной конфигурации (раздел 4.8, п.4):
-- {"slack_min": {"min": 0, "max": 15}, ...}. Пусто = переопределять нельзя ничего.
ALTER TABLE branch_config ADD COLUMN bounds jsonb NOT NULL DEFAULT '{}';
