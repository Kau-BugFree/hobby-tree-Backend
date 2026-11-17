# API-22 나무 성장 로드맵

`GET /api/v1/user-hobbies/{userHobbyId}/roadmap`은 HTTP 200 / `TREE200_4`와
기존 `ApiResponse`를 반환한다. 계약 기준은 `HobbyTree_API_본문_49개.md`의 API-22다.
JWT와 온보딩 완료가 필요하며, 본인 소유 나무만 조회한다. ACTIVE / PAUSED / ENDED 모두
조회할 수 있다. 없는 나무와 타인 나무는 동일하게 `TREE404_1`이다. 응답은 `Cache-Control:
no-store`를 적용한다. 인증·회원 상태·잘못된 ID 처리는 기존 공통 처리를 재사용한다.
`COMMON429_1`은 별도 공통 Rate Limit 범위이며 이 API에서 새 제한을 구현하지 않는다.

## 응답과 조회 기준

| 필드 | 값의 출처·계산 |
| --- | --- |
| userHobbyId | 본인 소유 `user_hobbies.user_hobby_id` |
| currentStageNumber | 저장된 `current_stage_number`에 `ApiStageNumber.fromStored()` 적용 |
| distinctCompletedActivityCount | API-18/20과 동일한 `countGrowthByTreeIds()`의 전체 `growth_counted=true` 기록 수 |
| growthRule.distinctActivitiesPerStage | 해당 `hobbies.activities_per_stage` |
| stages[].stageId | 실제 `hobby_stages.hobby_stage_id` |
| stages[].stageNumber | 실제 저장 단계 번호를 외부 1~6으로 한 번 변환 |
| stages[].name | 실제 `hobby_stages.stage_name` |
| stages[].state | 저장 단계가 현재보다 작으면 COMPLETED, 같으면 CURRENT, 크면 LOCKED |
| stages[].unlockAtDistinctCompletedCount | 저장 단계 번호 × 해당 취미의 `activities_per_stage` |
| stages[].representativeActivityIds | 해당 단계의 실제 PUBLISHED 활동 ID, ID 오름차순 |

내부 단계는 0~5, 외부는 1~6이다. ID·활동 수·해금 임계값에는 +1을 적용하지 않는다.
예를 들어 단계당 목표가 4이면 외부 1~6단계의 해금 기준은 0, 4, 8, 12, 16, 20이다.
이 식은 원문 예시의 0, 3, 6과 기존 `api-stage-number-contract-plan.md`를 일반화한 것으로,
예시의 목표 3이나 단계 이름·ID를 하드코딩하지 않는다.

`stages`는 실제 등록된 단계만 저장 번호 오름차순으로 반환한다. 미등록 단계를 생성하지
않으며, 단계가 전혀 없으면 `[]`이다. 현재 단계 정의가 없으면 CURRENT 항목도 없다.
중간 단계가 누락되어도 해금 기준은 배열 인덱스가 아닌 실제 저장 번호를 사용한다.
COMPLETED는 지나온 성장 단계라는 뜻이며, 해당 단계의 모든 활동을 완료했다는 뜻이 아니다.
최고 단계는 저장 5 / 외부 6이며, 이전 단계만 COMPLETED다.

## 대표 활동과 공개 범위

현재 DB에는 별도 대표 활동 지정·우선순위·노출 개수 필드가 없다. 원문도 예시 3개를 필수
개수로 규정하지 않는다. 이번 구현은 각 단계의 PUBLISHED 활동 전체를 ID 오름차순으로
반환한다. DRAFT / REVIEW / REJECTED는 제외하며 다른 취미의 활동도 포함하지 않는다.
LOCKED 단계의 공개 활동 ID도 로드맵으로 노출하되, 활동 시작 권한을 부여하는 것은 아니다.
공개 활동이 없으면 `[]`이며 예시 ID나 비공개 활동으로 채우지 않는다.
기획에서 별도의 대표 활동 큐레이션·개수 제한을 원하는 경우 후속 계약·카탈로그 설계가
필요하다. 실제 서비스의 대표 활동 선정 개수·우선순위는 추후 확정할 수 있다.
현재 선택 기준은 DB에서 검증 가능한 공개 상태와 ID뿐이다.

단계와 활동은 공개 조건을 ON 절에 둔 LEFT JOIN 스칼라 조회 한 번으로 가져온다.
활동 없는 단계도 유지하며, 서비스에서 stageId로 묶어 단계 중복을 제거한다.
성장 수는 별도 기존 집계 한 번을 재사용한다. 단계별 추가 조회나 지연 로딩을 사용하지
않는다. 읽기 전용 REPEATABLE READ 트랜잭션으로 소유권·나무·성장·카탈로그가 동일한
PostgreSQL 트랜잭션 스냅샷을 읽는다. 응답은 읽기 시점의 상태이며 이후 커밋은 포함하지 않는다.

## 누적 성장과 API-29 연동 시 주의

원문의 `activity_growth_contributions`는 신규 제안이며 현재 DB에 존재하지 않는다.
기존 `activity_records`의 `(user_hobby_id, activity_id)` UNIQUE 및 `growth_counted=true`
제약을 사용하므로 반복 시도 횟수는 누적 기여를 늘리지 않는다. COMPLETED여도 미인정 기록,
PARTIAL·IN_PROGRESS 및 다른 나무의 기록은 집계하지 않는다. 기여 인정 후 비공개로 변경된
활동도 역사적 기여는 유지한다. 대표 활동 공개 조건과 성장 집계 조건을 혼합하지 않는다.

API-18/20은 현재 단계의 `growth_stage_number=current_stage_number` 기여로 다음 승급까지
남은 수를 계산한다. 원문 API-29는 누적 고유 기여로 성장 단계를 갱신하도록 규정한다.
연속적으로 각 단계 목표를 채운 정상 데이터에서는 누적 해금 기준과 양립하지만, 과거
기여 부족·초과·재분류·목표 변경 데이터까지 같은 결과를 보장하는 것은 아니다.

이번 읽기 API는 저장된 현재 단계를 권위 있는 값으로 유지한다. 누적 수가 해금 기준과
맞지 않아도 자동 승급·강등·기여 재배정·데이터 보정을 하지 않는다. 활동이 부족하다고
목표를 줄이지 않는다. 단계·공개 활동 부족은 별도 카탈로그 정합성 검증 대상이다.

API-29 구현 시에는 다음 사항을 함께 결정·검증해야 한다.

API-29에서 UNIQUE 성장 기여 원장을 도입하면 API-18·20·22의 성장 집계도 동일한 원장을
사용하도록 일관되게 변경해야 한다. 이번 PR은 기존 `growth_counted` 집계를 유지하며,
신규 성장 원장·Flyway 마이그레이션·성장 단계 갱신 로직은 추가하지 않는다.

- 같은 활동 재완료·외부 참여와 공유하는 중복 없는 기여의 저장 방식. 현재 기록 UNIQUE는
  활동별 단일 행 모델이며, 여러 시도 기록 보존 설계와 구분해야 한다.
- 기록 종료·최초 기여 인정·내부 현재 단계 갱신을 하나의 트랜잭션에서 처리하고, 기존
  나무 상태 변경과의 잠금 순서·ACTIVE 검증을 준수한다.
- 누적 수에 따른 승급과 기존 현재 단계 기여 집계가 동일해지는 조건, 초과 기여 및 기존
  불일치 데이터 처리. 정책 합의 없이 API-18/20 계산을 변경하지 않는다.
- 변경된 내부 단계는 `ApiStageNumber.fromStored()`로 외부에 한 번만 매핑한다. API-22는
  새 저장 단계와 누적 수를 다음 조회에서 반영한다.

API-23~33, 새 기여 테이블, 마이그레이션, 전역 인증·응답 구조는 이번 작업에 포함하지 않는다.

## 검증

`UserHobbyRoadmapIntegrationTests`는 실제 HTTP·JWT·PostgreSQL·Redis를 사용한다.
신규 API-19 나무, 모든 내부 단계 0~5와 외부 1~6, 실제 성장 규칙, 상태 판정, 누적 기여와
반복 시도, 공개 활동 필터·정렬·다른 취미 제외, 빈/부분 단계, 모든 나무 상태 및 원본 데이터
보존, 소유권, 잘못된 ID, 토큰 누락·위조·만료·폐기와 회원 상태를 검증한다.
API-18/20의 단계·전체 성장 수를 직접 대조하며 기존 API-18~21 테스트도 유지한다.
전체 실행 결과와 격리 환경은 작업 완료 보고에 기록한다.

2026-11-07 최종 로컬 실행 기준은 develop
`e287885b462e4324030244af903e2aa8fe85ea2b`다. PostgreSQL 17.11 / Redis 7.4.12를
임시 컨테이너·별도 자격증명으로 실행했고 앱 DB 계정은 DML 권한만 부여했다.
JPA `validate`, open-in-view 비활성화 조건이다.

| 실행 경로 | 테스트 | 실패 / 오류 / 스킵 |
| --- | --- | --- |
| V1~V13 전체 스키마, 전체 Java·Flyway | 493 | 0 / 0 / 0 |
| V3 제외 CI 호환 스키마, 도메인·공통 오류 회귀 | 481 | 0 / 0 / 0 |

위 실행에는 각각 API-22 HTTP 통합 25개, API-18~21 HTTP 회귀 131개가 포함된다.
전체 실행의 Flyway 테스트는 12개이며 실제 XML 결과 검증 게이트도 통과했다.
Spotless, Java/테스트 컴파일, `git diff --check`도 통과했다. 위 수치는 로컬 검증 결과이며,
원격 GitHub CI·CodeRabbit 결과는 PR에서 별도로 확인한다. 운영 AWS/RDS 변경은 수행하지 않았다.
