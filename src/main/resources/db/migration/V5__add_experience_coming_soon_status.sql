-- API-40 클래스 오픈 알림 신청. DRAFT("비공개 작성 중")과 "공개됐지만 아직 예약 불가"를
-- 하나의 상태로 합치면 추후 목록 API가 DRAFT를 숨길 때 알림 신청 대상까지 같이 숨는
-- 문제가 생긴다. COMING_SOON을 별도 상태로 추가해 의미를 분리한다.
ALTER TABLE experiences DROP CONSTRAINT experiences_listing_status_check;
ALTER TABLE experiences ADD CONSTRAINT experiences_listing_status_check
    CHECK (listing_status IN ('DRAFT','COMING_SOON','OPEN','CLOSED','CANCELLED'));
