-- V18 belongs to activity content metadata on develop. This reservation migration is V19.
-- Keep legacy reservations intact: an occurrence cannot be guessed when none or several exist.
ALTER TABLE experience_reservations
    ADD COLUMN session_id BIGINT REFERENCES experience_sessions(session_id) ON DELETE CASCADE,
    ADD COLUMN provider_id BIGINT REFERENCES experience_providers(provider_id);

UPDATE experience_reservations r
SET session_id = only_session.session_id
FROM (
    SELECT experience_id, MIN(session_id) AS session_id
    FROM experience_sessions GROUP BY experience_id HAVING COUNT(*) = 1
) only_session
WHERE r.experience_id = only_session.experience_id;

UPDATE experience_reservations r SET provider_id = e.provider_id
FROM experiences e WHERE r.experience_id = e.experience_id;

ALTER TABLE experience_reservations
    DROP CONSTRAINT experience_reservations_experience_id_user_id_key;
ALTER TABLE experience_reservations
    ADD CONSTRAINT uq_experience_reservations_session_user UNIQUE (session_id, user_id);
CREATE UNIQUE INDEX uq_experience_reservations_legacy_experience_user
    ON experience_reservations(experience_id, user_id) WHERE session_id IS NULL;
-- provider_id IS NULL is excluded here: a plain btree index never treats two NULLs as equal,
-- so (NULL, same ref) rows would never collide and this index would silently give them no
-- protection at all. Scope that case to the experience instead (below) - a shared provider_id
-- is what makes a reference globally unique across experiences in the first place, and
-- without a provider there's nothing wider than the experience to scope it to.
CREATE UNIQUE INDEX uq_experience_reservations_provider_external_ref
    ON experience_reservations(provider_id, external_booking_ref)
    WHERE external_booking_ref IS NOT NULL AND provider_id IS NOT NULL;
CREATE UNIQUE INDEX uq_experience_reservations_no_provider_external_ref
    ON experience_reservations(experience_id, external_booking_ref)
    WHERE external_booking_ref IS NOT NULL AND provider_id IS NULL;

-- REQUESTED has no verified replacement and must not silently become a confirmed booking.
ALTER TABLE experience_reservations
    DROP CONSTRAINT experience_reservations_reservation_status_check;
ALTER TABLE experience_reservations
    ADD CONSTRAINT experience_reservations_reservation_status_check CHECK (reservation_status IN
        ('REQUESTED','REDIRECTED','PENDING_VERIFICATION','CONFIRMED','CANCELLED','ATTENDED'));
