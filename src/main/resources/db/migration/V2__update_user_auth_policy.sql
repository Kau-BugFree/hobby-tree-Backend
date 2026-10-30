-- Structural change only. Flyway runs each migration in a single transaction
-- on PostgreSQL, so a guard or constraint added in this same file would roll
-- back these columns the moment it fails, leaving no committed columns for a
-- reviewed backfill to target. The guard and the constraints that depend on
-- a completed backfill live in V3 instead, after this migration commits.
ALTER TABLE users
    ADD COLUMN restorable_until TIMESTAMPTZ,
    ADD COLUMN status_before_withdrawal VARCHAR(24),
    ADD COLUMN anonymized_at TIMESTAMPTZ,
    ALTER COLUMN provider DROP NOT NULL,
    ALTER COLUMN provider_user_id DROP NOT NULL;

-- restorable_until is fully determined by the fixed 720-hour policy enforced
-- by ck_users_withdrawal_lifecycle in V3, so backfilling it from withdrawn_at
-- here is not a guess. status_before_withdrawal cannot be derived the same
-- way and is intentionally left for a reviewed backfill (see V3).
UPDATE users
SET restorable_until = withdrawn_at + INTERVAL '720 hours'
WHERE account_status = 'WITHDRAWN'
  AND restorable_until IS NULL;
