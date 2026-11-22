-- API-26 candidate; requires the V27 growth ledger prerequisite.
LOCK TABLE activity_records IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM activity_records WHERE activity_status='IN_PROGRESS'
               GROUP BY user_hobby_id HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Multiple historical in-progress records require explicit resolution'
            USING ERRCODE='23514';
    END IF;
END;
$$;

ALTER TABLE activity_records
    ADD COLUMN record_format SMALLINT NOT NULL DEFAULT 1 CHECK(record_format IN (1,2)),
    ADD COLUMN record_version BIGINT CHECK(record_version>0),
    ADD COLUMN elapsed_seconds INTEGER CHECK(elapsed_seconds BETWEEN 0 AND 86400),
    ADD CONSTRAINT ck_record_format_metadata CHECK (
        record_format=1 OR (record_version IS NOT NULL AND elapsed_seconds IS NOT NULL AND started_at IS NOT NULL));
ALTER TABLE activity_records DROP CONSTRAINT activity_records_user_hobby_id_activity_id_key;
CREATE UNIQUE INDEX uq_records_one_in_progress ON activity_records(user_hobby_id)
    WHERE activity_status='IN_PROGRESS';
CREATE UNIQUE INDEX uq_records_one_growth_origin ON activity_records(user_hobby_id,activity_id)
    WHERE growth_counted;
CREATE INDEX idx_records_tree_activity ON activity_records(user_hobby_id,activity_id,activity_record_id DESC);

CREATE TABLE activity_record_contents (
    record_id BIGINT PRIMARY KEY REFERENCES activity_records(activity_record_id) ON DELETE CASCADE,
    snapshot_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK(snapshot_schema_version=1),
    content_version BIGINT NOT NULL CHECK(content_version>0),
    content_updated_at TIMESTAMPTZ,
    captured_at TIMESTAMPTZ NOT NULL,
    title VARCHAR(160) NOT NULL CHECK(length(btrim(title))>0),
    content_snapshot JSONB NOT NULL CHECK(jsonb_typeof(content_snapshot)='object')
);
CREATE TABLE activity_record_steps (
    record_id BIGINT NOT NULL REFERENCES activity_records(activity_record_id) ON DELETE CASCADE,
    step_id BIGINT NOT NULL CHECK(step_id>0),
    step_number SMALLINT NOT NULL CHECK(step_number>0),
    title VARCHAR(160) NOT NULL CHECK(length(btrim(title))>0),
    description TEXT NOT NULL,
    is_required BOOLEAN NOT NULL,
    image_url TEXT,
    is_completed BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY(record_id,step_id),
    UNIQUE(record_id,step_number)
);

CREATE FUNCTION protect_activity_record_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND NOT EXISTS (
        SELECT 1 FROM activity_records WHERE activity_record_id=OLD.record_id) THEN
        RETURN OLD; -- Account/tree/record purge cascade; catalog edits cannot reach this path.
    END IF;
    IF TG_TABLE_NAME='activity_record_steps' AND TG_OP='INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM activity_record_contents WHERE record_id=NEW.record_id) THEN
            RETURN NEW; -- Steps are inserted before the final immutable content row.
        END IF;
    ELSIF TG_TABLE_NAME='activity_record_steps' AND TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-'is_completed') IS NOT DISTINCT FROM (to_jsonb(OLD)-'is_completed') THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'Activity snapshot identity and content are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER protect_record_content BEFORE UPDATE OR DELETE ON activity_record_contents
    FOR EACH ROW EXECUTE FUNCTION protect_activity_record_snapshot();
CREATE TRIGGER protect_record_steps BEFORE INSERT OR UPDATE OR DELETE ON activity_record_steps
    FOR EACH ROW EXECUTE FUNCTION protect_activity_record_snapshot();

CREATE FUNCTION require_activity_start_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM activity_records WHERE activity_record_id=NEW.activity_record_id AND record_format=2)
       AND (NOT EXISTS (SELECT 1 FROM activity_record_contents WHERE record_id=NEW.activity_record_id)
            OR NOT EXISTS (SELECT 1 FROM activity_record_steps WHERE record_id=NEW.activity_record_id AND is_required)) THEN
        RAISE EXCEPTION 'A new activity record requires a complete start snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER require_record_snapshot AFTER INSERT OR UPDATE ON activity_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_activity_start_snapshot();

COMMENT ON COLUMN activity_records.record_format IS
    '1=original summary format without fabricated snapshot/version/time; 2=captured self-tracked attempt';
COMMENT ON COLUMN activity_record_steps.step_id IS
    'Original step identity copied at start, deliberately no live catalog step FK';
