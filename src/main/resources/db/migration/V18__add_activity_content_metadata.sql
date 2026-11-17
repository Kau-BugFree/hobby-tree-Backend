-- Additive expansion only: do not invent legacy titles, required flags or historical times.
ALTER TABLE activities
    ADD COLUMN thumbnail_url TEXT,
    ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1 CHECK (content_version > 0),
    ADD COLUMN content_updated_at TIMESTAMPTZ,
    ADD COLUMN content_change_xid xid8,
    ADD CONSTRAINT activities_thumbnail_nonblank CHECK (thumbnail_url IS NULL OR btrim(thumbnail_url) <> '');
-- Existing rows deliberately remain NULL: child modification history is unknown.
ALTER TABLE activities ALTER COLUMN content_updated_at SET DEFAULT clock_timestamp();
COMMENT ON COLUMN activities.content_version IS 'Baseline 1 at introduction; increments once per activity per writing transaction, not historical edit count.';
COMMENT ON COLUMN activities.content_updated_at IS 'Known content creation/change time; NULL for legacy content until a known post-baseline change. Not inferred from parent updated_at.';
COMMENT ON COLUMN activities.content_change_xid IS 'DB-internal top-level transaction token for aggregate version coalescing; never exposed as an API version.';

ALTER TABLE activity_steps
    ADD COLUMN title VARCHAR(160),
    ADD COLUMN is_required BOOLEAN,
    ADD COLUMN image_url TEXT,
    ADD CONSTRAINT activity_steps_title_nonblank CHECK (title IS NULL OR btrim(title) <> ''),
    ADD CONSTRAINT activity_steps_image_nonblank CHECK (image_url IS NULL OR btrim(image_url) <> '');
COMMENT ON COLUMN activity_steps.is_required IS 'NULL denotes uncurated legacy semantics, not optional. Never infer from instruction.';

CREATE FUNCTION activity_content_revision() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    content_changed BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- SHARE, unlike KEY SHARE, also blocks non-key inherited metadata edits.
        PERFORM h.hobby_id FROM public.hobbies h
          JOIN public.hobby_stages hs ON hs.hobby_id = h.hobby_id
          WHERE hs.hobby_stage_id = NEW.hobby_stage_id FOR SHARE OF h;
        NEW.content_version := 1;
        NEW.content_updated_at := clock_timestamp();
        NEW.content_change_xid := pg_current_xact_id();
        RETURN NEW;
    END IF;
    content_changed := ROW(NEW.activity_name, NEW.hobby_stage_id, NEW.short_description,
        NEW.detailed_description, NEW.expected_active_minutes, NEW.expected_total_minutes,
        NEW.estimated_cost_min, NEW.estimated_cost_max, NEW.difficulty, NEW.required_conditions,
        NEW.safety_notes, NEW.review_status, NEW.thumbnail_url)
      IS DISTINCT FROM ROW(OLD.activity_name, OLD.hobby_stage_id, OLD.short_description,
        OLD.detailed_description, OLD.expected_active_minutes, OLD.expected_total_minutes,
        OLD.estimated_cost_min, OLD.estimated_cost_max, OLD.difficulty, OLD.required_conditions,
        OLD.safety_notes, OLD.review_status, OLD.thumbnail_url);
    -- Child/inherited changes request a touch through content_updated_at.
    IF content_changed OR NEW.content_updated_at IS DISTINCT FROM OLD.content_updated_at THEN
        NEW.content_version := OLD.content_version
          + CASE WHEN OLD.content_change_xid IS DISTINCT FROM pg_current_xact_id() THEN 1 ELSE 0 END;
        NEW.content_change_xid := pg_current_xact_id();
        NEW.content_updated_at := greatest(clock_timestamp(),
            OLD.content_updated_at + interval '1 microsecond');
    ELSE
        NEW.content_version := OLD.content_version;
        NEW.content_updated_at := OLD.content_updated_at;
        NEW.content_change_xid := OLD.content_change_xid;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER activities_content_revision
BEFORE INSERT OR UPDATE ON activities
FOR EACH ROW EXECUTE FUNCTION activity_content_revision();

CREATE FUNCTION touch_activity_content(target_id BIGINT) RETURNS void
LANGUAGE sql SET search_path = pg_catalog, public AS $$
    UPDATE public.activities
       SET content_updated_at = greatest(clock_timestamp(), content_updated_at + interval '1 microsecond')
     WHERE activity_id = target_id;
$$;

CREATE FUNCTION activity_child_content_revision() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    old_parent BIGINT;
    new_parent BIGINT;
    target_id BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' THEN old_parent := OLD.activity_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_parent := NEW.activity_id; END IF;
    FOR target_id IN SELECT DISTINCT p FROM unnest(ARRAY[old_parent, new_parent]) p
                    WHERE p IS NOT NULL ORDER BY p
    LOOP
        PERFORM public.touch_activity_content(target_id);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER activity_steps_content_revision
AFTER INSERT OR UPDATE OR DELETE ON activity_steps
FOR EACH ROW EXECUTE FUNCTION activity_child_content_revision();
CREATE TRIGGER activity_materials_content_revision
AFTER INSERT OR UPDATE OR DELETE ON activity_materials
FOR EACH ROW EXECUTE FUNCTION activity_child_content_revision();

CREATE FUNCTION hobby_inherited_content_revision() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE target_id BIGINT;
BEGIN
    FOR target_id IN
        SELECT a.activity_id FROM public.activities a
          JOIN public.hobby_stages hs ON hs.hobby_stage_id = a.hobby_stage_id
         WHERE hs.hobby_id = NEW.hobby_id
           AND (NEW.indoor_outdoor IS DISTINCT FROM OLD.indoor_outdoor
             OR NEW.is_supported IS DISTINCT FROM OLD.is_supported
             OR (a.thumbnail_url IS NULL AND NEW.thumbnail_url IS DISTINCT FROM OLD.thumbnail_url))
         ORDER BY a.activity_id
    LOOP
        PERFORM public.touch_activity_content(target_id);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER hobbies_inherited_content_revision
AFTER UPDATE OF indoor_outdoor, thumbnail_url, is_supported ON hobbies
FOR EACH ROW EXECUTE FUNCTION hobby_inherited_content_revision();

CREATE FUNCTION stage_inherited_content_revision() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE target_id BIGINT;
BEGIN
    IF NEW.stage_number IS DISTINCT FROM OLD.stage_number OR NEW.hobby_id IS DISTINCT FROM OLD.hobby_id THEN
        FOR target_id IN SELECT activity_id FROM public.activities
                         WHERE hobby_stage_id = NEW.hobby_stage_id ORDER BY activity_id
        LOOP
            PERFORM public.touch_activity_content(target_id);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER hobby_stages_inherited_content_revision
AFTER UPDATE OF stage_number, hobby_id ON hobby_stages
FOR EACH ROW EXECUTE FUNCTION stage_inherited_content_revision();

