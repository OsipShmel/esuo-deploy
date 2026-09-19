-- монотонная последовательность вместо created_at (в одну миллисекунду бывает несколько событий).
ALTER TABLE outbox ADD COLUMN seq bigserial;
CREATE INDEX ix_outbox_pending_seq ON outbox (seq) WHERE status = 'pending';
