-- Метрики отделений из heartbeat (план финала, B2/C3b): сырые данные за 48 ч, пишутся пачками вместе с heartbeat.
-- sys — системный блок ноды (CPU, память, пул БД, диск, HTTP, связь), queue — очередь и окна на тот момент.
-- Одна строка на отделение не чаще esuo.center.heartbeat.metrics-every (по умолчанию раз в минуту): при 40 000
-- отделений каждые 10 с таблица за 48 ч выросла бы до 700 млн строк.
CREATE TABLE branch_metrics (
  branch_id  text        NOT NULL,
  ts         timestamptz NOT NULL,
  sys        jsonb,
  queue      jsonb       NOT NULL
);
CREATE INDEX ix_branch_metrics_branch_ts ON branch_metrics (branch_id, ts);
CREATE INDEX ix_branch_metrics_ts ON branch_metrics (ts);
