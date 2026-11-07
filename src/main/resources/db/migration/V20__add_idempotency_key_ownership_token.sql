-- claim()/complete()/release() had no way to tell whether the caller invoking them still
-- owned the row. A request stuck waiting minutes for an unrelated lock can have its PENDING
-- claim reclaimed by a retry (PENDING_LEASE assumes the original caller crashed); if the
-- original caller then finishes anyway and calls complete()/release(), it could silently
-- overwrite or delete whatever the reclaiming caller has since written. Every claim/reclaim
-- now mints a fresh token, and complete()/release() only act when it still matches.
ALTER TABLE idempotency_keys ADD COLUMN ownership_token VARCHAR(36);
