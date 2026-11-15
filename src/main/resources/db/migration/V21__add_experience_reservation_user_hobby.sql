-- API-38 (내 예약 목록) needs userHobbyId in its response, but API-37 never persisted the
-- create request's userHobbyId onto the row - it only used it transiently to validate the
-- hobby match at creation time, then echoed the request value straight back in the create
-- response. Add the column so a later list read can actually recover it.
ALTER TABLE experience_reservations
    ADD COLUMN user_hobby_id BIGINT REFERENCES user_hobbies(user_hobby_id) ON DELETE SET NULL;
