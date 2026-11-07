# API-23 취미별 단계 목록 조회

계약 기준은 `HobbyTree_API_본문_49개.md`의 API-23 및 이번 작업에서 확정한 조회 정책이다.
`GET /api/v1/hobbies/{hobbyId}/stages`는 HTTP 200 / `ACTIVITY200_1`,
메시지 `취미 단계 목록을 조회했습니다.`와 기존 `ApiResponse`를 반환한다.
다른 활동 API, 성장 단계 갱신, 새 Flyway는 포함하지 않는다.

## 인증과 공개 범위

- JWT, 회원 상태는 기존 공통 인증 필터를 사용한다. 온보딩은 기존 HobbyService와 같은
  계정 조회·PENDING_ONBOARDING 검사로 `AUTH403_2`를 반환한다.
- 유효하지 않은 hobbyId는 `COMMON400_1`, 토큰 누락·위조·만료·폐기는 `AUTH401_2`,
  정지·탈퇴는 각각 기존 `AUTH403_1` / `AUTH403_3`이다.
- API-17과 동일하게 `HobbyRepository.findById()` 후 `isSupported=true`를 확인한다.
  없는 취미와 비공개 취미는 기존 `HobbyErrorCode.DETAIL_NOT_FOUND`를 재사용하여
  HTTP 404 / `HOBBY404_1` / `취미가 없거나 비공개 상태임`을 반환한다.
- 나무를 심었는지 검사하지 않는다. 개인의 나무·기여·잠금 상태와 무관한 카탈로그다.
- 성공 응답은 `Cache-Control: no-store`를 적용한다.
- `COMMON429_1`은 별도 공통 Rate Limit 범위로서 이번 작업에서는 새로 구현하지 않는다.
  예상하지 못한 오류는 기존 `COMMON500_1` 공통 처리로 전달한다.

## 필드와 계산

| 필드 | 출처·계산 |
| --- | --- |
| result.hobbyId | 실제 hobbies.hobby_id |
| result.hobbyName | 실제 hobbies.hobby_name |
| result.stages | 실제 등록된 단계, 저장 단계 번호 오름차순 |
| stages[].stageId | 실제 hobby_stages.hobby_stage_id |
| stages[].stageNumber | `ApiStageNumber.fromStored(stage_number)`로 내부 0~5를 외부 1~6으로 변환 |
| stages[].name | 실제 stage_name |
| stages[].description | 실제 stage_description; DB NULL은 JSON null 유지 |
| stages[].activityCount | 해당 단계의 PUBLISHED 활동 수 |
| stages[].unlockAtDistinctCompletedCount | 내부 stage_number × 해당 취미의 activities_per_stage |

성장 목표 3개를 하드코딩하지 않는다. 예를 들어 단계당 목표가 4인 취미의 외부 1~6단계
해금 기준은 0, 4, 8, 12, 16, 20이다. 단계가 부분 등록되어도 배열 인덱스가 아닌 실제
저장 번호로 계산한다. 저장 번호·이름·설명은 변경하지 않으며 누락된 단계를 생성하지 않는다.

단계가 없으면 `stages=[]`, 공개 활동이 없으면 `activityCount=0`이다.
DRAFT / REVIEW / REJECTED는 집계하지 않으며 다른 취미의 활동도 제외한다.
카운트와 해금 임계값은 정수로 반환하며 `state`, `isLocked` 등 개인별 필드는 추가하지 않는다.
개인별 잠금 상태는 API-22 나무 로드맵에서 조회한다.

## 조회 및 기존 API 영향

`HobbyStageRepository.findStageSummaries()`는 PUBLISHED 조건을 ON 절에 둔 LEFT JOIN과
GROUP BY / COUNT(activity_id)를 사용한다. 공개 활동이 없는 단계도 유지하면서 한 번의
집계 쿼리로 모든 단계의 활동 수를 가져온다. 엔티티·활동 목록을 응답에 노출하거나
단계마다 추가 조회하지 않는다. 단계 ID별 한 행을 반환하고, 저장 번호·ID 순으로 정렬한다.

읽기 전용 REPEATABLE READ 트랜잭션으로 공개 여부 확인·성장 규칙·단계 집계를 같은
PostgreSQL 트랜잭션 스냅샷에서 읽는다. 기존 HobbyController / HobbyService / Repository
구조를 사용하며 기존 API-16·17 메서드나 API-18~22 동작은 바꾸지 않는다.
성공 코드는 HobbyController에서 제공하는 카탈로그 경로와 맞춰 HobbySuccessCode에
STAGES를 추가하되, 코드 문자열은 노션 계약의 `ACTIVITY200_1`을 그대로 사용한다.

## 명세와 DB 차이

원문은 "활성화된 단계 정의"를 조회하도록 서술하지만 현재 hobby_stages에는 활성화·공개
컬럼이 없다. 이번에 확정된 정책에 따라 공개 취미에 등록된 단계 전체를 반환한다.
활동 공개 여부는 activities.review_status로 구분한다. 활성화 컬럼·새 상태·임의 필터를
추가하지 않는다. 단계별 활성화가 필요해지면 별도 계약·스키마 작업으로 결정해야 한다.

원문 예시의 이름·설명·활동 수는 실제 DB 값으로 대체하며 설명 누락을 임의 문구로 채우지
않는다. 현재 확정된 API-23 구현을 막는 스키마 문제는 없다. API-22의 성장 기여 집계나
향후 API-29의 성장 원장 설계에는 이번 작업으로 변경을 가하지 않는다.

## 검증 범위

`HobbyStageIntegrationTests`는 실제 HTTP/JWT와 PostgreSQL·Redis를 사용한다.
전체 응답 필드, 외부 1~6 및 저장값 보존, 역순 삽입 후 단계 정렬, 실제 설명과 null,
공개 활동 집계, 빈/부분 단계, 취미별 성장 규칙, 비공개·없는 취미, 잘못된 ID, JWT 및
온보딩·정지·탈퇴를 검증한다. 나무가 없는 회원도 조회할 수 있음을 검증하고, API-19로
심은 뒤 API-22의 번호·해금 기준·대표 활동 수와 대조한다.

SQL 통계로 단계가 1개에서 6개로 늘어나도 실제 HTTP 조회의 준비된 SQL 수가 동일한지
검증한다. 통계 활성화는 테스트 안에서만 적용하고 원래 설정으로 복원한다.
전체 Java·Flyway 및 V3 제외 CI 호환 스키마 경로의 테스트 결과는 완료 보고에 기록한다.

## 최종 로컬 검증 결과 (2026-11-07)

기준 develop은 `b3d61ff033099d892d767fa8daf30f71321dc6fb`이며,
`feat/api-23-hobby-stage-list` / `tmp/backend-api-23`에서 실행했다.
PostgreSQL 17.11 / Redis 7.4.12를 매 실행 별도 임시 컨테이너·자격증명으로 사용했다.
앱 DB 계정은 DML·시퀀스 사용 권한만 보유하며 JPA validate / open-in-view=false로 검증했다.

| 실행 | 테스트 | 실패 / 오류 / 스킵 |
| --- | ---: | --- |
| V1~V13 전체 스키마, 전체 Java·Flyway | 515 | 0 / 0 / 0 |
| V3 제외 CI 호환 스키마, 도메인·공통 오류 회귀 | 503 | 0 / 0 / 0 |

두 실행에는 각각 API-23 HTTP 통합 22개, 기존 API-16·17 HTTP 회귀 12개,
API-18~22 HTTP 회귀 156개가 포함된다. 전체 실행의 Flyway는 12개이며 실제 XML 결과 검증
게이트도 통과했다. Spotless, Java·테스트 컴파일, git diff --check도 통과했다.
단계 1개와 6개를 조회하는 실제 HTTP 요청의 SQL 수가 동일함을 검증했다.
기존 단계·성장·상태 API 및 Flyway 파일은 변경하지 않았다.

위 수치는 로컬 검증 결과이며 최종 커밋에서 재실행하고 원격 CI·CodeRabbit 결과는 PR에서
별도로 확인한다. 운영 AWS/RDS 변경은 수행하지 않았다.
