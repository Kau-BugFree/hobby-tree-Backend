# 내 취미 나무 목록 (API-18)

`GET /api/v1/user-hobbies`는 Bearer 인증을 거쳐 본인 나무를 반환한다. 성공 응답은 HTTP 200과
`TREE200_1`이며, 나무가 없으면 `items`는 빈 배열이다. `status`를 생략하면 종료한 나무도 조회된다.
슬롯 집계는 페이지와 필터에 관계없이 ACTIVE·PAUSED 상태를 센다.

result.items[].currentStageNumber는 외부1~6이다. DB0~5는 그대로 두고 응답 매핑에서만 +1한다.
단계 이름은 변환 전 저장 번호로 조회한다.

- `distinctCompletedActivityCount`는 해당 나무의 전체 성장 기여 수다. 기존 `activity_records`의
  `growth_counted`와 나무·활동 유니크 제약을 사용한다. 다음 단계까지 남은 수는
  `hobbies.activities_per_stage`에서 현재 단계의 성장 기여 수를 빼고 0 이상으로 제한한다.
  저장된 단계가 5이면 `requiredCountForNextStage`는 `null`, `remainingCountForNextStage`는 0이다.
- 현재 단계에 해당하는 `hobby_stages` 행이 있으면 `stage_name`을 반환한다. 현재 스키마는 나무의
  단계 번호와 단계 정의 사이의 존재 관계를 강제하지 않는다. 단계 정의가 누락된 경우
  `currentStageName`을 `null`로 반환한다. 명세 예시는 문자열이며 누락 시 응답은 정의하지 않는다.
- 목록·슬롯·단계 이름·성장 집계는 한 API 호출의 PostgreSQL `REPEATABLE READ` 읽기 트랜잭션에서
  조회한다. 다음 커서 페이지는 별도 요청의 스냅샷을 사용한다.
- 명세에 있는 `COMMON429_1`과 `Retry-After`는 공통 Rate Limit 적용 범위다. 현재 저장소의
  호출 제한은 로그인·토큰 재발급·로그아웃·계정 복구에 적용되며, API-18용 공통 호출 제한은 미구현이다.
