-- Reuse existing satisfaction/perceived_difficulty; no duplicate feedback source.
ALTER TABLE activity_records ADD COLUMN feedback_memo TEXT
    CONSTRAINT ck_activity_record_feedback_memo_length CHECK(char_length(feedback_memo)<=1000);
COMMENT ON COLUMN activity_records.feedback_memo IS
    'Optional private completion feedback memo; legacy absence stays NULL, not fabricated';
