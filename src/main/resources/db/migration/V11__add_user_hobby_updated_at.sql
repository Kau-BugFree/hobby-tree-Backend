-- API-21. Merge/apply PR #93's V10 first; do not deploy V11 ahead of V10.
-- Existing timestamps are only the best available initialization evidence.
-- In particular, an earlier ACTIVE resume cannot be reconstructed from these columns.
ALTER TABLE user_hobbies ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE user_hobbies
SET updated_at = GREATEST(planted_at, paused_at, ended_at);

ALTER TABLE user_hobbies
    ALTER COLUMN updated_at SET DEFAULT clock_timestamp(),
    ALTER COLUMN updated_at SET NOT NULL;

-- Use wall-clock time at the actual write, not now()/CURRENT_TIMESTAMP, whose
-- transaction-start value can predate a previous writer while waiting for a lock.
CREATE FUNCTION set_user_hobby_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'updated_at') IS DISTINCT FROM (to_jsonb(OLD) - 'updated_at') THEN
        NEW.updated_at := clock_timestamp();
    ELSE
        NEW.updated_at := OLD.updated_at;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_hobbies_updated_at_before_update
BEFORE UPDATE ON user_hobbies
FOR EACH ROW EXECUTE FUNCTION set_user_hobby_updated_at();
