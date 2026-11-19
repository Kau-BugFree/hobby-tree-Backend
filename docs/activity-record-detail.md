# API-27 활동 진행 상황 조회

> 2026-11-19 통합 계약: [활동 APIv2](api-v2-activities.md) · [코드·명세 대조/후속](activity-contract-status.md). 구현·병합과 운영 반영 상태는 구분한다.

Issue #232. 선행 API-26 #231이 병합된 develop 640b75e를 기준으로 한다.
`GET /api/v1/activity-records/{recordId}`, JWT, 양의 recordId, HTTP200 / RECORD200_2.

- ACTIVE 회원만 본인 소유 기록을 조회한다. 타인/없는 기록은 같은 RECORD404_1이며 캐시는 no-store다.
- 시작 snapshot의 title/contentVersion/steps와 저장된 version/elapsedSeconds/시각/피드백을 반환한다.
- 실행 단계와 완료 목록은 snapshot stepNumber,stepId 오름차순이다. 필수 완료 비율의 정수 내림이며 선택 단계는 완료 목록에만 포함한다. 100%만으로 종료하지 않는다.
- IN_PROGRESS + ACTIVE 나무일 때만 canUpdate=true다. PAUSED/ENDED 나무와 종료 기록도 열람한다.
- 종료 feedback는 기존 satisfaction/perceived_difficulty와 새 feedback_memo를 매핑한다. 저장된 값이 모두 없으면 null이며 공백/Unicode를 바꾸거나 피드백을 생성하지 않는다.
- 원본 카탈로그 조회/공개 여부를 사용하지 않는다. 실제 활동 hard delete는 기존 FK로 계속 차단된다.
- REPEATABLE_READ 읽기 전용 트랜잭션에서 회원·소유 기록·현재 나무·snapshot·체크를 읽어 동시 저장의 version과 단계가 섞이지 않게 한다. 조회로 시각/버전/진행을 변경하지 않는다.

## 사용자 확정 명세 보완 — 2026-11-19

시작 snapshot 없는 과거/체험 인정 기록은 보존하고 HTTP409 RECORD409_5로 정비 필요를 알린다.
이 오류를 APIv2 API-27에도 적용하도록 사용자가 승인했다. 본인 기록을404로 숨기거나 현재 콘텐츠/가짜 단계·시간·버전으로 정상 응답을 만들지 않는다.

## 스키마·후속 범위

V29는 activity_records.feedback_memo nullable TEXT와 1000자 제한만 추가한다. 기존 만족도·난이도를 재사용하며 제안 테이블명 activity_feedback에 중복 보관하지 않는다. 이는 기존 활동 기록 시스템 설계와 일치한다. V1~V28은 변경하지 않고 과거 메모를 백필하지 않는다.

도입 후 API-28~30·50도 구현·병합됐다. 현재 owner 조회는 삭제 기록을 먼저 제외해 RECORD404_1이며 나무 상태·과거 스냅샷 오류보다 앞서 처리한다. 종료 상태·피드백 검증에는 명시적 TEST ONLY fixture를 사용한다.
운영 AWS·Secret·DB·배포·마이그레이션·실제 모델 호출은 수행하지 않는다. 전체 수명주기 검증 전 운영 공개는 별도다.
