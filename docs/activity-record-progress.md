# API-28 활동 단계 진행 저장

> 2026-11-19 통합 계약: [활동 APIv2](api-v2-activities.md) · [코드·명세 대조/후속](activity-contract-status.md). 구현·병합과 운영 반영 상태는 구분한다.

Issue #234. develop e03b77a의 API-26/27·V29를 기준으로 한다.
`PATCH /api/v1/activity-records/{recordId}/progress`, JWT, HTTP200/RECORD200_3.

- ACTIVE 회원의 본인 기록만 저장한다. 타인/없는 기록은 RECORD404_1이다.
- 회원→소유 나무→소유 기록 순서로 잠그고 ACTIVE 나무·IN_PROGRESS·시작 snapshot·expectedVersion을 재검증한다.
- completedStepIds는 필수이며 []로 전체 해제한다. 모든 ID는 시작 snapshot에 속해야 하고 중복이 없어야 한다. 순서를 강제하지 않으며 선택 단계는 완료 목록에만 포함한다.
- 필수 완료 비율을 정수 내림한다. 100%여도 IN_PROGRESS이며 성장·종료·피드백은 변경하지 않는다.
- 타입 오류·필수 누락·양수가 아닌 버전은 COMMON400_1, snapshot 밖/중복 ID·0~86400 밖 시간은 RECORD400_2다. JSON 정수의 소수 절삭·문자열 변환은 거절한다.
- 같은 값이라도 현재 expectedVersion으로 성공 저장하면 version+1이다. stale retry는 RECORD409_3이다. 단계 체크 전체 교체와 부모 version CAS·시간 저장은 같은 트랜잭션이며 실패하면 전부 rollback한다.
- updatedAt은 DB clock_timestamp와 직전 시각+1µs 중 큰 값이다. 잠금 대기 전 트랜잭션 시작 시각을 사용하지 않으며 DB 저장 정밀도로 응답한다.
- API-27과 API-26 이어하기에서 저장된 체크·시간·버전을 확인할 수 있다. API-20의 format2 진행 요약도 같은 시작 snapshot의 제목/진행률로 연결한다. format1의 미확정 진행률은 기존 null을 유지한다.

## 사용자 확정 명세 보완 — 2026-11-19

- 시작 snapshot 없는 과거 진행은 보존하고 RECORD409_5로 저장을 차단한다. 이미 종료된 기록은 RECORD409_2다. 가짜 snapshot/버전/시간을 만들지 않는다.
- elapsedSeconds 생략/null은 기존 값을 유지한다. 제공하면 0~86400 보고값을 그대로 저장하며 감소도 허용한다. max() 보정이나 서버 경과 시간 추정, actual_minutes 덮어쓰기는 하지 않는다.

## 검증·범위

기존 V1~V29와 Entity 스키마를 유지하며 새 migration은 없다. 실제 콘텐츠 대신 TEST ONLY fixture를 사용한다.
API-29 완료/성장·API-30 목록·API-50 삭제도 구현·병합됐다. 현재 owner 조회는 삭제 기록을 먼저 제외해 RECORD404_1이며 나무 상태·종료/스냅샷 검증보다 앞선다.
모든 진행 writer는 같은 잠금·버전 규약을 따라야 하며, 운영자 직접 DML까지 버전 자동 증가로 보호한다고 주장하지 않는다.
운영 AWS·Secret·DB·배포·마이그레이션·실제 AI 호출은 수행하지 않는다.
