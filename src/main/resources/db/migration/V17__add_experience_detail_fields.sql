-- API-36 (클래스·모임 상세): materials is a plain JSON array of description strings - no
-- per-item metadata needed (unlike activity_materials' is_required flag), since the client
-- just displays this list verbatim.
ALTER TABLE experiences
    ADD COLUMN materials JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN cancellation_policy_url TEXT;

-- Per-session capacity, so remainingSeats can be computed per occurrence instead of sharing
-- one capacity across every recurrence of a class. Nullable like experiences.capacity itself
-- (no cap recorded == no limit); existing session rows inherit their parent's capacity so
-- current behavior doesn't silently change.
ALTER TABLE experience_sessions ADD COLUMN capacity INTEGER CHECK (capacity > 0);

UPDATE experience_sessions es
SET capacity = e.capacity
FROM experiences e
WHERE e.experience_id = es.experience_id AND e.capacity IS NOT NULL;
