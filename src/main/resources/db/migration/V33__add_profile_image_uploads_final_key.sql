-- Issue #277: tracks the S3 key copyToFinalKey copies into once an upload is linked, so a
-- cleanup job can tell whether that copy is still referenced by users.profile_image_key or was
-- superseded by a later upload (replaced/reset). Existing LINKED rows predate this column and
-- stay NULL forever - that historical final key was never recorded anywhere and can't be
-- recovered, so the cleanup job leaves those older rows' linked image alone and only cleans up
-- their now-redundant original object_key.
ALTER TABLE profile_image_uploads ADD COLUMN final_key VARCHAR(255);

-- Supports the cleanup scheduler's two scans: expired-unlinked ISSUED rows, and every LINKED
-- row (to check whether its final_key is still the user's current profile_image_key).
CREATE INDEX idx_profile_image_uploads_cleanup ON profile_image_uploads (status, expires_at);
