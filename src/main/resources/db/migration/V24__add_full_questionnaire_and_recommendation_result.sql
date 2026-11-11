-- Additive only: legacy preferences/recommendations remain readable and unmodified.
ALTER TABLE user_preferences ADD COLUMN questionnaire JSONB
    CHECK (questionnaire IS NULL OR jsonb_typeof(questionnaire) = 'object');
ALTER TABLE recommendations ADD COLUMN engine_result JSONB
    CHECK (engine_result IS NULL OR jsonb_typeof(engine_result) = 'object');
