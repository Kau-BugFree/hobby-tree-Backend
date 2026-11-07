-- API-37 (외부 예약 정보 연동·기록): experience_reservations predates experience_sessions
-- (V16) and only ever linked a reservation to the experience as a whole. A class can recur
-- across multiple sessions, so a member booking two different occurrences of the same class
-- must be allowed - replace the (experience_id,user_id) uniqueness with (session_id,user_id).
-- This table has never been written to by any shipped feature (API-37/38/39 don't exist yet),
-- so there is no existing data to backfill a session_id for.
ALTER TABLE experience_reservations
    ADD COLUMN session_id BIGINT NOT NULL
        REFERENCES experience_sessions(session_id) ON DELETE CASCADE;

ALTER TABLE experience_reservations
    DROP CONSTRAINT experience_reservations_experience_id_user_id_key;
ALTER TABLE experience_reservations
    ADD CONSTRAINT uq_experience_reservations_session_user UNIQUE (session_id, user_id);

-- Denormalized from the experience at creation time (not a live join), so the uniqueness
-- below doesn't depend on the experience's provider ever changing afterward.
ALTER TABLE experience_reservations
    ADD COLUMN provider_id BIGINT REFERENCES experience_providers(provider_id);

-- "외부 예약 식별자의 중복 제약은 제공자 단위(provider_id, external_reservation_id)로
-- 적용" (API.md 비고) - partial: only enforced once an external reference was actually
-- submitted (REDIRECTED rows have none yet).
CREATE UNIQUE INDEX uq_experience_reservations_provider_external_ref
    ON experience_reservations(provider_id, external_booking_ref)
    WHERE external_booking_ref IS NOT NULL;

-- REDIRECTED (no external reference submitted) and PENDING_VERIFICATION (one submitted, not
-- yet provider-confirmed) are API-37's two possible creation-time statuses - neither existed
-- before this table had only REQUESTED/CONFIRMED/CANCELLED/ATTENDED.
ALTER TABLE experience_reservations
    DROP CONSTRAINT experience_reservations_reservation_status_check;
ALTER TABLE experience_reservations
    ADD CONSTRAINT experience_reservations_reservation_status_check
        CHECK (reservation_status IN
            ('REDIRECTED','PENDING_VERIFICATION','CONFIRMED','CANCELLED','ATTENDED'));
