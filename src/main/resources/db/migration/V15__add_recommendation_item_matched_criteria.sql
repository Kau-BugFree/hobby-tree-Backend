-- API-14 Part 1 stored only the free-text recommendation_reason, never the matchedCriteria
-- tags it displayed in the same response - API-15 (최근 추천 결과 조회) needs to echo those
-- tags back from storage, not recompute them against the current (possibly different) state.
ALTER TABLE recommendation_items
    ADD COLUMN matched_criteria JSONB NOT NULL DEFAULT '[]'::jsonb;
