# 취미 나무 상세 및 성장 현황 (API-20)

`GET /api/v1/user-hobbies/{userHobbyId}`는 본인 나무에 HTTP 200 · `TREE200_2`를 반환한다.
타인 소유와 없는 나무는 같은 `TREE404_1`이다. ACTIVE·PAUSED·ENDED 모두 열람 가능하다.
온보딩 미완료는 `AUTH403_2`, 양의 정수 형식·범위를 벗어난 ID는 기존 공통 `COMMON400_1`로 처리한다.

## 성장과 단계

API-18의 공통 항목 매핑과 성장 집계 쿼리를 그대로 사용한다.
`distinctCompletedActivityCount`는 전체 `growth_counted=true` 기록 수다.
기존 나무·활동 UNIQUE 제약으로 반복 시도가 누적 기여를 늘리지 않는다.
`currentStageCompletedCount`는 `growth_stage_number=current_stage_number`인 기여 수다.
다음 단계 목표는 `activities_per_stage`, 남은 수는 현재 단계 기여 수를 뺀 0 이상의 값이다.
저장된 0~5단계를 유지하고 최고 5단계에서는 `isMaxStage=true`, 다음 목표는 null, 남은 수는 0이다.
`currentStageRequiredCount`도 다음 승급 목표를 의미한다. 최고 단계의 null은 다음 승급 목표가 없다는 뜻이다.

`currentStageName`은 실제 단계 정의에서 찾고, 없으면 API-18과 같이 null이다.
2026-11-07 MVP 계약에 따라 `totalStages`는 정책상 전체 성장 단계 수 6이다.
DB 현재 단계는0~5, 외부 result.currentStageNumber는1~6이다. API-18의 이미 변환된 Item값을
그대로 복사하며 추가 +1은 하지 않는다. isMaxStage는 저장5(외부6)일 때만 true다.
`hobby_stages`가 부분 등록되거나 미등록이어도 `totalStages=6`을 반환한다.
실제 등록 수와 0~5단계 정의의 누락은 별도의 카탈로그 데이터 정합성 검증 대상이다.
조회에서 누락된 단계나 이름을 생성하지 않는다. 원본 명세의 `totalStages: 3` 예시는 6으로 수정이 필요하다.

## 진행 기록과 계산 제약

IN_PROGRESS 기록의 ID, 활동 ID와 현재 `activities.activity_name`을 반환한다.
기록이 없으면 `currentActivityRecord=null`이다.
현재 DB는 여러 활동의 동시 진행을 허용하므로 `updated_at DESC, activity_record_id DESC`의 첫 기록을 선택한다.
향후 API-26에서 나무당 한 진행 기록 정책을 구현하면 이 선택은 그 단일 기록을 반환한다.

`progressPercent`의 정확한 숫자 계산은 보류한다. 진행 기록 객체가 존재하면
`progressPercent: null`을 JSON에 명시적으로 포함한다. 원본 API-20 예시의 숫자와 다른 임시 nullable 계약이다.
API-27·28의 공식은 시작 당시 필수 단계 중 완료한 비율의 정수 내림이다. 현재 `step_progress`는
기본값이 빈 JSONB이며 완료 단계 저장 규칙과 콘텐츠 스냅샷이 없다. 현재 `activity_steps`에도
필수 여부나 시작 당시 버전이 없어 현행 단계 수를 분모로 쓰면 콘텐츠 변경·선택 단계에서 오산할 수 있다.
JSON 안의 임의 `progressPercent` 값도 신뢰하지 않는다.
숫자 진행률을 완성하려면 API-26의 시작 스냅샷과 API-27·28의 완료 단계 저장·조회 계약을 확정하고
같은 계산을 재사용해야 한다. 연동 전까지 null은 진행률 미확인을 뜻한다.

`lastActivityAt`은 이 나무 활동 기록의 `MAX(updated_at)`이며 기록이 없으면 null이다.
시작·재개·완료 시각 중 하나만 고르면 진행 저장을 놓칠 수 있어 기록의 최근 변경 시각을 사용했다.
이는 사용자 행동 이벤트 시각이나 최근 완료 시각과는 구분되며, 향후 정비·피드백 수정도 반영될 수 있다.

조회는 API-18과 같은 PostgreSQL REPEATABLE READ 읽기 트랜잭션에서 수행한다.
`COMMON429_1`은 별도 공통 Rate Limit 범위다.
숫자 진행률은 API-26~28 연동 후 구현할 후속 항목으로 남는다.
