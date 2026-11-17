-- API-11 온보딩 답변 저장 및 수정. The 16 columns dropped below (hobby_status through
-- additional_consideration) predate API.md's finalized onboarding design - no service code
-- has ever read or written any of them (same situation V9 cleaned up for
-- user_locations/location_consent_history). notification_frequency/updated_at are kept: API-46
-- already reads and writes notification_frequency.
ALTER TABLE user_preferences
    DROP COLUMN hobby_status,
    DROP COLUMN first_activity_budget,
    DROP COLUMN first_budget_status,
    DROP COLUMN monthly_budget,
    DROP COLUMN session_minutes,
    DROP COLUMN weekly_frequency,
    DROP COLUMN preferred_environment,
    DROP COLUMN companion_preference,
    DROP COLUMN activity_selection_style,
    DROP COLUMN record_style,
    DROP COLUMN preferred_hobbies,
    DROP COLUMN goals,
    DROP COLUMN owned_assets,
    DROP COLUMN exclusions,
    DROP COLUMN hobby_specific_answers,
    DROP COLUMN custom_interest_text,
    DROP COLUMN additional_consideration,
    ADD COLUMN options_version VARCHAR(40),
    ADD COLUMN budget_level VARCHAR(10) CHECK (budget_level IN ('LOW', 'MEDIUM', 'HIGH')),
    ADD COLUMN available_time_minutes INT CHECK (available_time_minutes IN (15, 30, 60, 120)),
    ADD COLUMN indoor_outdoor VARCHAR(10) CHECK (indoor_outdoor IN ('INDOOR', 'OUTDOOR', 'ANY')),
    ADD COLUMN difficulty_preference VARCHAR(20)
        CHECK (difficulty_preference IN ('BEGINNER', 'INTERMEDIATE', 'ANY')),
    -- 0~3 fixed category ids (see HobbyCategoryCatalog); jsonb, same storage convention the
    -- table's old preferred_hobbies/goals columns used for list-shaped values.
    ADD COLUMN preferred_category_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 0 means "no preferences saved yet" - distinct from the row simply existing, since a row
    -- can already exist here for a user who has only ever touched notification_frequency.
    ADD COLUMN preferences_version INT NOT NULL DEFAULT 0;
