-- Preserve the legacy ID order without inventing editorial ranks for existing content.
ALTER TABLE activities ADD COLUMN display_order INTEGER;
ALTER TABLE activities ADD CONSTRAINT activities_display_order_nonnegative
    CHECK (display_order IS NULL OR display_order >= 0);

COMMENT ON COLUMN activities.display_order IS
    'Stage-local ascending discovery rank; ties use activity_id; NULL is unassigned and sorts last. Does not change execution content_version.';

CREATE INDEX idx_activities_published_stage_display_order
    ON activities (hobby_stage_id, display_order ASC NULLS LAST, activity_id)
    WHERE review_status = 'PUBLISHED';
