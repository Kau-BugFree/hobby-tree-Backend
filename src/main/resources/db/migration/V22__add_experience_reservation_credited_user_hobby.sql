-- API-39: records which tree a reservation's participation was credited to, independent of
-- whether this activity's growth was newly counted or already credited via another
-- reservation/tree. activity_records' own (user_hobby_id, activity_id) uniqueness means a
-- second reservation crediting the same activity can't also claim credited_activity_record_id
-- (that column stays null for it), so this is the one place "which tree did THIS reservation
-- confirm attendance for" is always recoverable - needed to detect "이미 다른 나무에 이미
-- 인정됨" on a repeat request with a different userHobbyId.
ALTER TABLE experience_reservations
    ADD COLUMN credited_user_hobby_id BIGINT REFERENCES user_hobbies(user_hobby_id) ON DELETE SET NULL;
