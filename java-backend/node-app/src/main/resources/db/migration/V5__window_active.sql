-- Мягкое удаление окон руководителем (раздел 9.3: «удаление — мягкое»): окно остаётся в БД ради истории талонов.
ALTER TABLE windows ADD COLUMN active boolean NOT NULL DEFAULT true;
