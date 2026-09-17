-- Префикс номера однозначно определяет услугу в отделении.
ALTER TABLE services ADD CONSTRAINT ux_service_number_prefix UNIQUE (branch_id, number_prefix);

-- Справочники одного отделения нельзя связать с талоном другого отделения.
ALTER TABLE services ADD CONSTRAINT ux_service_branch UNIQUE (id, branch_id);
ALTER TABLE windows ADD CONSTRAINT ux_window_branch UNIQUE (id, branch_id);
ALTER TABLE employees ADD CONSTRAINT ux_employee_branch UNIQUE (id, branch_id);
ALTER TABLE tickets ADD CONSTRAINT fk_ticket_service_branch FOREIGN KEY (service_id, branch_id) REFERENCES services(id, branch_id);
ALTER TABLE tickets ADD CONSTRAINT fk_ticket_window_branch FOREIGN KEY (window_id, branch_id) REFERENCES windows(id, branch_id);
ALTER TABLE tickets ADD CONSTRAINT fk_ticket_target_branch FOREIGN KEY (target_window_id, branch_id) REFERENCES windows(id, branch_id);
ALTER TABLE tickets ADD CONSTRAINT fk_ticket_employee_branch FOREIGN KEY (employee_id, branch_id) REFERENCES employees(id, branch_id);
ALTER TABLE windows ADD CONSTRAINT fk_window_employee_branch FOREIGN KEY (current_employee_id, branch_id) REFERENCES employees(id, branch_id);

-- Значения для новых услуг задаются конфигурацией, а не неявными значениями базы.
ALTER TABLE services ALTER COLUMN slot_duration_min DROP DEFAULT;
ALTER TABLE services ALTER COLUMN slot_capacity DROP DEFAULT;

-- Журнал допускает только добавление событий.
CREATE FUNCTION reject_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Журнал событий нельзя изменять или очищать';
END;
$$;
CREATE TRIGGER ticket_events_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON ticket_events
  FOR EACH STATEMENT EXECUTE FUNCTION reject_event_mutation();
CREATE TRIGGER window_events_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON window_events
  FOR EACH STATEMENT EXECUTE FUNCTION reject_event_mutation();
