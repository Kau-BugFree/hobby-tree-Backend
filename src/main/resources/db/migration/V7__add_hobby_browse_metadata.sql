-- API-16 전체 취미 탐색 및 검색에 필요한 카탈로그 메타데이터. 카테고리는 팀 결정상
-- 요리/악기/독서 3종으로 고정돼 있어(2026-11-04 회의), 별도 hobby_categories lookup
-- 테이블 대신 CHECK 제약이 있는 컬럼으로 둔다.
-- DEFAULT를 두는 이유: 다른 도메인의 기존 테스트(Activity/Report/SponsoredContent/User)가
-- hobbies를 그냥 "소속 취미" fixture로만 써서 hobby_code/hobby_name만 넣고 만든다 -
-- 그 테스트들은 카테고리·실내외 여부를 전혀 신경 쓰지 않으므로, 전부 고치는 대신 안전한
-- 기본값으로 호환성을 유지한다. 실제 카탈로그 데이터는 이 값들을 명시적으로 채운다.
ALTER TABLE hobbies
    ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'COOKING'
        CHECK (category IN ('COOKING', 'INSTRUMENT', 'READING')),
    ADD COLUMN thumbnail_url TEXT,
    ADD COLUMN indoor_outdoor VARCHAR(10) NOT NULL DEFAULT 'INDOOR'
        CHECK (indoor_outdoor IN ('INDOOR', 'OUTDOOR'));
