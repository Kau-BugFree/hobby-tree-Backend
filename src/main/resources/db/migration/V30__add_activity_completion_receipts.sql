ALTER TABLE activity_records ADD COLUMN not_done_reason VARCHAR(200)
    CONSTRAINT ck_activity_not_done_reason_length CHECK (char_length(not_done_reason) BETWEEN 1 AND 200);

CREATE TABLE activity_completion_receipts (
    record_id BIGINT PRIMARY KEY REFERENCES activity_records(activity_record_id) ON DELETE CASCADE,
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK(schema_version=1),
    request_payload JSONB NOT NULL CHECK(jsonb_typeof(request_payload)='object'),
    response_payload JSONB NOT NULL CHECK(jsonb_typeof(response_payload)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION protect_activity_completion_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND NOT EXISTS (
        SELECT 1 FROM activity_records WHERE activity_record_id=OLD.record_id) THEN
        RETURN OLD; -- Retain existing account/tree purge cascade, not catalogue deletion.
    END IF;
    RAISE EXCEPTION 'First completion receipt is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER protect_completion_receipt BEFORE UPDATE OR DELETE ON activity_completion_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_activity_completion_receipt();
COMMENT ON TABLE activity_completion_receipts IS
    'First API-29 canonical request (without expectedVersion) and result; no fabricated legacy receipts';
