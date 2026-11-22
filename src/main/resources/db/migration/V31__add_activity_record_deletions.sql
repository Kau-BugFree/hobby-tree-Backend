-- API-50: existing facts and first completion receipts are not backfilled or rewritten.
ALTER TABLE activity_records ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_deleted_activity_is_terminal CHECK (
        deleted_at IS NULL OR activity_status IN ('COMPLETED','PARTIAL','NOT_DONE'));
-- Historical credited_stage_number remains immutable in application code. A revocation may
-- redistribute the current progress buckets without falsifying where a credit was first earned.
ALTER TABLE activity_growth_contributions ADD COLUMN progress_stage_number SMALLINT
    CHECK(progress_stage_number BETWEEN 0 AND 5);

CREATE TABLE activity_record_deletions (
    record_id BIGINT PRIMARY KEY REFERENCES activity_records(activity_record_id) ON DELETE CASCADE,
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK(schema_version=1),
    deleted_at TIMESTAMPTZ NOT NULL,
    growth_action VARCHAR(20) NOT NULL CHECK(growth_action IN ('NONE','REASSIGN','REVOKE')),
    growth_before JSONB NOT NULL CHECK(jsonb_typeof(growth_before)='object'),
    growth_after JSONB NOT NULL CHECK(jsonb_typeof(growth_after)='object'),
    response_payload JSONB NOT NULL CHECK(jsonb_typeof(response_payload)='object')
);
CREATE FUNCTION protect_activity_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='activity_records' THEN
        IF OLD.deleted_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Deleted activity records cannot be changed or restored' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' AND NOT EXISTS (
        SELECT 1 FROM activity_records WHERE activity_record_id=OLD.record_id) THEN
        RETURN OLD; -- Retain existing account/tree purge cascade.
    END IF;
    RAISE EXCEPTION 'First activity deletion and growth audit are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER protect_deleted_record BEFORE UPDATE ON activity_records
    FOR EACH ROW EXECUTE FUNCTION protect_activity_deletion();
CREATE TRIGGER protect_deletion_receipt BEFORE UPDATE OR DELETE ON activity_record_deletions
    FOR EACH ROW EXECUTE FUNCTION protect_activity_deletion();

CREATE FUNCTION require_activity_deletion_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target BIGINT;
BEGIN
    IF TG_TABLE_NAME='activity_records' THEN target=NEW.activity_record_id;
    ELSE target=NEW.record_id; END IF;
    IF EXISTS (SELECT 1 FROM activity_records r WHERE r.activity_record_id=target AND r.deleted_at IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM activity_record_deletions d
                        WHERE d.record_id=target AND d.deleted_at=r.deleted_at))
       OR EXISTS (SELECT 1 FROM activity_record_deletions d JOIN activity_records r
                  ON r.activity_record_id=d.record_id WHERE d.record_id=target
                  AND r.deleted_at IS DISTINCT FROM d.deleted_at) THEN
        RAISE EXCEPTION 'Deletion requires its matching immutable first result' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER require_record_deletion AFTER INSERT OR UPDATE ON activity_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_activity_deletion_receipt();
CREATE CONSTRAINT TRIGGER require_deletion_record AFTER INSERT ON activity_record_deletions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_activity_deletion_receipt();
COMMENT ON COLUMN activity_growth_contributions.progress_stage_number IS
    'Current bucket after authorized API-50 redistribution; NULL uses original credited_stage_number';
COMMENT ON TABLE activity_record_deletions IS
    'First API-50 result and before/after credit, origin, provenance and mirror audit; no legacy fabrication';
