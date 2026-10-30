-- Existing withdrawn accounts need an explicitly verified pre-withdrawal status.
-- Do not guess ACTIVE for accounts that may not have completed onboarding.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE account_status = 'WITHDRAWN') THEN
        RAISE EXCEPTION 'Existing WITHDRAWN users require a reviewed backfill plan before V2 can run';
    END IF;
END $$;

ALTER TABLE users
    ADD COLUMN restorable_until TIMESTAMPTZ,
    ADD COLUMN status_before_withdrawal VARCHAR(24),
    ADD COLUMN anonymized_at TIMESTAMPTZ,
    ALTER COLUMN provider DROP NOT NULL,
    ALTER COLUMN provider_user_id DROP NOT NULL;

-- V1 uses unnamed CHECK constraints. Locate only the status/withdrawal checks
-- by their column references rather than relying on generated constraint names.
DO $$
DECLARE
    existing_constraint RECORD;
BEGIN
    FOR existing_constraint IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'users'::regclass
          AND c.contype = 'c'
          AND c.conkey && ARRAY(
              SELECT a.attnum
              FROM pg_attribute a
              WHERE a.attrelid = 'users'::regclass
                AND a.attname IN ('account_status', 'withdrawn_at')
          )::SMALLINT[]
    LOOP
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', existing_constraint.conname);
    END LOOP;
END $$;

ALTER TABLE users
    ADD CONSTRAINT ck_users_account_status CHECK (
        account_status IN ('PENDING_ONBOARDING', 'ACTIVE', 'WITHDRAWN', 'SUSPENDED', 'ANONYMIZED')
    ),
    ADD CONSTRAINT ck_users_social_identity CHECK (
        (account_status = 'ANONYMIZED' AND provider IS NULL AND provider_user_id IS NULL)
        OR
        (account_status <> 'ANONYMIZED' AND provider IS NOT NULL AND provider_user_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_users_withdrawal_lifecycle CHECK (
        (account_status = 'WITHDRAWN'
            AND withdrawn_at IS NOT NULL
            AND restorable_until IS NOT NULL
            AND restorable_until = withdrawn_at + INTERVAL '720 hours'
            AND status_before_withdrawal IS NOT NULL
            AND status_before_withdrawal IN ('PENDING_ONBOARDING', 'ACTIVE')
            AND anonymized_at IS NULL)
        OR
        (account_status = 'ANONYMIZED'
            AND withdrawn_at IS NULL
            AND restorable_until IS NULL
            AND status_before_withdrawal IS NULL
            AND anonymized_at IS NOT NULL
            AND nickname IS NULL
            AND profile_image_key IS NULL
            AND age_range IS NULL)
        OR
        (account_status IN ('PENDING_ONBOARDING', 'ACTIVE', 'SUSPENDED')
            AND withdrawn_at IS NULL
            AND restorable_until IS NULL
            AND status_before_withdrawal IS NULL
            AND anonymized_at IS NULL)
    );

CREATE INDEX idx_users_withdrawn_restorable_until
    ON users (restorable_until) WHERE account_status = 'WITHDRAWN';

-- Redis owns authentication sessions and token hashes; no SQL session table.
-- A separate application job must clear related personal data and transition
-- expired WITHDRAWN accounts to ANONYMIZED. This migration does not run that job.
