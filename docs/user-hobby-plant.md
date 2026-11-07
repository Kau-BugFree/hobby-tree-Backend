# 취미 나무 심기 (API-19)

`POST /api/v1/user-hobbies`는 필수 `hobbyId`와 선택 `recommendationId`를 받는다.
HTTP 201과 `TREE201_1`로 생성한 나무를 반환한다. 직접 탐색은 추천 ID를 생략한다.
추천 ID가 있으면 본인 추천 묶음과 해당 취미 항목의 존재를 모두 확인한다.

회원 행의 기존 `PESSIMISTIC_WRITE` 잠금 획득 후 ACTIVE 계정, 공개 취미, 중복 및 슬롯을 검사한다.
심기 트랜잭션은 `READ COMMITTED`를 사용해 잠금 대기 중 다른 요청이 생성한 나무를 후속 조회에서 확인한다.
같은 취미의 ENDED 행도 `TREE409_1` 중복이며, 슬롯은 ACTIVE·PAUSED 5그루를 기준으로 한다.
기존 나무·취미 UNIQUE 위반도 `TREE409_1`로 변환하고 해당 트랜잭션을 롤백한다.

## MVP 시작 단계 정책

2026-11-07 MVP 정책 결정에 따라 내부 성장 단계는 0~5이며 새 나무는 0단계·ACTIVE로 시작한다.
기존 UserHobby·Flyway 기본값과 API-16·API-18의 번호 체계를 유지한다.
`HobbyTree_API_본문_49개.md`의 API-19 로직 4번과 응답 예시 `currentStageNumber: 1`은
0단계 시작으로 수정이 필요하다. 저장된 단계에 1을 더하거나 단계 정의를 합성하지 않는다.

Hobby 행은 `PESSIMISTIC_READ` 공유 잠금으로 공개 여부를 보호하며 사용자 행 잠금 뒤에 획득한다.
서로 다른 회원이 같은 취미를 심을 때 이 공유 잠금끼리는 양립한다. 한 번의 심기는 회원 한 행과
취미 한 행만 잠그며, API-19에는 취미를 먼저 잠근 뒤 회원을 잠그는 경로가 없다.

`COMMON429_1`은 별도 공통 Rate Limit 범위다. 중복 POST는 `TREE409_1`이며 별도 멱등성 키는 사용하지 않는다.
필드 제약 검증 실패의 `COMMON400_1`은 기존 공통 예외 처리에 따라 `result`에 필드별 오류를 담는다.
명세의 `result: null` 오류 예시와 차이가 있으며, 잘못된 JSON·필드 타입 오류는 기존대로 `null`이다.
