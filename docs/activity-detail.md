# API-25 활동 상세·콘텐츠 버전

## 기준·범위

- 기준 origin/develop: 3f5ac56f5f03e7ad646d5b10430f6bb1ffa86974 (2026-11-07 재조회).
- 브랜치 feat/api-25-activity-detail / worktree tmp/backend-api-25.
- 원문: HobbyTree_API_본문_49개.md API-25. 별도 설계 worktree의 activity-content-schema-plan.md는 보존한다.
- 신규 migration V17__add_activity_content_metadata.sql **임시 예약**. V1~V15 수정 없음.
- PR #121은 OPEN이며 V16__redesign_experience_region_and_sessions.sql을 사용한다.
  팀원 V16은 이 브랜치에 가져오거나 수정하지 않았다. 현재 검증 경로는 V1~V15 + V17이다.
  **PR #121 병합 후 실제 V16을 포함한 V15→V16→V17 적용·전체 회귀 전에는 이 PR을 병합하지 않는다.**
- PostgreSQL 17·Redis 로컬 격리 검증과 Issue·Draft PR/원격 리뷰 단계다. 운영 AWS/RDS 변경·배포·병합은 금지한다.
- API-26~30, 성장 원장, 기록 UNIQUE 변경, 스냅샷 테이블은 구현하지 않는다.

## HTTP 계약과 조회

GET /api/v1/activities/{activityId}, HTTP 200 / ACTIVITY200_3 / 활동 상세를 조회했습니다.
기존 ApiResponse, Cache-Control: no-store를 사용한다.

JWT·현재 계정 ACTIVE 및 온보딩 완료를 요구한다. 나무 존재/성장 단계/소유 나무는 요구하지 않으며
잠긴 단계도 미리 볼 수 있다. PUBLISHED 활동과 is_supported=true 취미만 조회한다.
존재하지 않거나 비공개인 경우 ACTIVITY404_1 / 활동이 없거나 비공개 상태임이다.
API-31/32의 기존 NOT_SAVABLE 메시지는 유지한다. 잘못된 ID는 COMMON400_1, 인증 실패는 AUTH401_2,
온보딩 미완료·정지·탈퇴는 AUTH403_2/1/3, 예기치 못한 오류는 COMMON500_1이다.
COMMON429_1은 별도 공통 Rate Limit 미구현 범위다. 전역 인증·예외 메시지 정책은 바꾸지 않는다.

| 응답 | DB 매핑·정책 |
| --- | --- |
| activityId / title | activity_id / activity_name |
| thumbnailUrl | activity.thumbnail_url → hobby.thumbnail_url → null |
| durationMinutes / estimatedCost | expected_total_minutes / estimated_cost_min |
| difficulty / indoorOutdoor | 활동 difficulty 원본 / 취미 indoor_outdoor |
| isSaved | 요청 사용자와 활동의 복합 PK 존재 여부. 다른 사용자 저장 데이터 제외 |
| hobbyId / stageId / stageNumber | 실제 소속, ApiStageNumber.fromStored()로 저장 0~5 → 외부 1~6 |
| description | detailed_description 그대로. short_description으로 자동 대체하지 않음 |
| contentVersion / contentUpdatedAt | DB에서 관리하는 콘텐츠 버전 / 알려진 콘텐츠 생성·변경 시각 |
| materials[] | materialId, name, quantity, isRequired, acquisitionType; materialId ASC |
| steps[] | stepId, stepNumber, title, description, isRequired, imageUrl; step_number ASC |
| cautions[] | safety_notes null/공백이면 []; 그 외 원문 전체를 문자열 한 항목으로 보존 |

steps.description은 instruction 원문이다. 실행 단계 stepNumber는 원래부터 양의 정수이므로 성장 번호처럼 +1하지 않는다.
빈 준비물·실행 단계는 []다. nullable는 키를 생략하지 않고 JSON null을 유지한다.
개행/쉼표/JSON처럼 보이는 safety_notes를 임의 분해하지 않는다.
획득 방식은 기존 문자열/NULL 그대로이며 NULL을 NONE으로 바꾸지 않는다.
주의사항 목록 저작 방식, acquisitionType enum/NULL, 상세 설명 NULL에 대한 최종 콘텐츠 계약은 추가 승인이 필요하다.

읽기 전용 REPEATABLE READ 트랜잭션에서 사용자, 활동+단계+취미 JOIN FETCH, 준비물, 실행 단계,
저장 여부를 읽는다. 다중 collection join으로 행을 곱하지 않으며 항목 수와 무관하게 repository 조회는 5회다.
인증 필터 쿼리는 별도다. 한 요청의 여러 SELECT는 같은 DB snapshot을 사용한다.
다른 요청까지 고정된 snapshot은 아니며, RR 조회가 즉시 최신 회원 정지 커밋까지 항상 반영한다고 설명하지 않는다.

## V17 확장·무손실 이관

| 컬럼 | 정의 |
| --- | --- |
| activities.thumbnail_url | TEXT NULL, 빈 문자열 금지 |
| activities.content_version | BIGINT NOT NULL DEFAULT 1, CHECK >0 |
| activities.content_updated_at | TIMESTAMPTZ NULL; 기존 행은 NULL 유지, 신규 INSERT 기본값/trigger는 실제 생성 시각 |
| activities.content_change_xid | xid8 NULL, 내부 트랜잭션별 버전 병합 토큰. API·JPA 버전으로 노출하지 않음 |
| activity_steps.title | VARCHAR(160) NULL, 있으면 공백만인 값 금지, 기본값 없음 |
| activity_steps.is_required | BOOLEAN NULL, 기본값 없음. NULL은 미확정이며 false가 아님 |
| activity_steps.image_url | TEXT NULL, 있으면 공백만인 값 금지 |

기존 활동·실행 단계·준비물 ID와 원문은 보존한다. 기록·저장·나무·성장 기여 행을 수정하지 않는다.
기존 제목/필수 여부/이미지/획득 방식/과거 수정 시각을 추론해 백필하지 않는다.
기존 활동의 contentVersion=1은 **기능 도입 baseline**이며 과거 편집 횟수나 시작 당시 버전이 아니다.
기존 contentUpdatedAt=NULL은 **과거 aggregate 수정 시각을 모름**이다. 기존 updated_at을 복사하지 않는다.
도입 후 실제 변경이 발생하면 버전은 2부터 증가하고 실제 DB 변경 시각이 저장된다.
새 활동은 실제 생성 시각과 version=1을 저장한다.

이는 설계 문서의 “baseline 등록 시각을 contentUpdatedAt으로 사용하는 후보”보다 보수적인 단계적 이관이다.
역사적 변경 시각을 새로 만들지 않기 위해 NULL을 선택했다. 원문의 non-null 예시와는 임시 계약 차이다.
기존 콘텐츠에 의미 있는 제목/필수 여부가 없으면 API-25는 해당 키를 null로 반환한다.
이 상태를 완전 계약 충족으로 판단하지 않는다. 임의 404나 가짜 제목/true로 감추지 않는다.
전역 NOT NULL·획득 enum CHECK·PUBLISHED 콘텐츠 강제 전환·새 공개 검증 trigger는 추가하지 않는다.
후속 콘텐츠 정비와 FE/기획 nullable 승인 또는 근거 있는 값 확보가 **정식 출시 gate**다.

정비 절차:
1. 승인된 환경에서 읽기 전용으로 PUBLISHED의 title/is_required/description/acquisition_type 누락·기타값을 조사한다.
2. 콘텐츠 담당자가 stepId별 제목·필수 여부, 필요한 자료·이미지·획득 방식을 제공한다.
3. 활동 단위의 짧은 트랜잭션으로 검증된 값만 반영하고 버전/시각·대상 ID·근거를 기록한다.
4. 실제 자료 확보 후 별도 migration에서 공개 콘텐츠 정합성 제약을 강화한다. V17을 재편집하지 않는다.

## 버전·시각 갱신 경로

DB trigger가 단일 소유자다. 콘텐츠 버전을 @Version으로 쓰지 않는다.

- 루트 이름·소속 단계·설명·시간·비용·난이도·요구조건·주의사항·활동 이미지·공개 상태의 실제 변경.
- 준비물/실행 단계 INSERT·UPDATE·DELETE. 재부착 시 이전/새 활동 양쪽을 갱신한다.
- 취미 실내외/공개 상태 변경은 관련 활동에 전파한다. 취미 이미지 변경은 활동 전용 이미지가 없는 활동에만 전파한다.
- 성장 단계 소속/번호의 실제 변경도 관련 활동에 전파한다. 이번 API가 재분류 편집 기능을 제공하지는 않는다.
- 같은 값 UPDATE·0행 UPDATE와 단순 activities.updated_at/reviewed_at 등 메타데이터 변경은 제외한다.
- saved_activities·사용자 기록·성장 기여 변경도 제외한다.

pg_current_xact_id()의 top-level xid8를 내부 토큰으로 사용하여 같은 활동의 여러 변경을 한 트랜잭션에서
**최대 한 번** 가산한다. 부모·자식 trigger 간 이중 가산을 하지 않는다.
새 활동과 그 초기 자식을 같은 트랜잭션에서 생성하면 최종 콘텐츠도 version=1이다.
contentUpdatedAt은 그 트랜잭션에서 마지막으로 처리한 실제 변경 시각까지 갱신할 수 있다.
같은 버전의 중간 미커밋 상태는 외부 요청에서 볼 수 없다. rollback/savepoint rollback은 row 값과 함께 되돌아간다.
A→B→A처럼 실제 변경을 실행한 트랜잭션은 최종 본문이 같아도 한 번 증가할 수 있다.
버전은 배포 횟수나 본문 hash가 아니다.

JPA는 DB-generated version/시각을 INSERT/UPDATE 때 읽는다.
자식만 수정했다면 부모 managed entity가 저절로 갱신되지 않으므로 flush 후 부모 refresh가 필요하다.
직접 SQL·벌크 변경 후 기존 persistence context의 오래된 부모 값을 재사용하지 않는다.

## API-26 시작 스냅샷과 편집 잠금 규약

이번에는 스냅샷/기록 API를 구현하지 않았다. 다음 방식의 기반만 검증한다.

- 미래 시작 요청은 기존 user → user_hobby 잠금 후 hobby 공유 잠금 → activity FOR SHARE 순서로 획득한다.
- 잠금 후 소속·공개 여부·회원/나무 상태를 재검증하고 같은 트랜잭션에서 준비물/단계를 일반 SELECT로 읽는다.
- FOR KEY SHARE만으로는 비키 콘텐츠 UPDATE를 막지 못하므로 사용하지 않는다.
- 자식 변경은 커밋 전에 부모의 충돌 UPDATE/버전 touch를 거친다. SHARE 보유 시작 요청 동안 변경 커밋이 대기한다.
- 신규 activity INSERT도 hobby FOR SHARE로 상속 메타데이터 변경과 경쟁을 조정한다.
- 편집 writer는 hobby → activity → 자식의 사전 잠금 순서를 지킨다. 여러 부모는 ID 오름차순이다.
  stage 소속/번호 변경과 관계 이동도 해당 상위/활동들을 사전 잠그는 승인된 절차가 필요하다.
- 직접 임의 DML·TRUNCATE·trigger 비활성화를 허용하는 환경까지 무교착/불변 snapshot을 보장하지 않는다.
  교착은 전체 rollback하며 전체 콘텐츠 편집 API·권한 설계는 후속 범위다.
- 원본 단계 삭제 후에도 기록을 보존하려면 향후 record_steps는 live step FK 대신 복사한 stepId/본문/필수 여부를 저장한다.
  현재 record UNIQUE/성장 집계는 이번에 바꾸지 않는다. 필수 단계 0개의 시작 거절은 API-26에서 검증한다.
- API-26 신규 시작은 필수 단계 0개뿐 아니라 title/isRequired가 미정비(NULL)인 실행 단계가 있는 콘텐츠도 거절해야 한다.
  NULL을 false로 간주해 분모를 줄이거나 미확정 콘텐츠를 snapshot으로 확정하지 않는다. 거절 오류 매핑은 API-26 계약 검토 범위다.
- 활동 이미지 URL을 snapshot에 복사해도 파일 자체의 영구 보존은 아니다. 불변 자산명·보존 정책이 별도로 필요하다.

물리 백업/PITR과 별개로 논리 복원·클러스터 이동 시 내부 xid8 토큰 재초기화/충돌 방지 절차도 검토해야 한다.
이 내부 토큰을 외부 영속 revision 식별자로 사용하지 않는다. 복원/데이터 정비 SQL은 이번에 실행하지 않는다.

## API-24 및 Issue #118

API-24와 API-25는 같은 이미지 우선순위와 총 시간/최소 비용/난이도/실내외/개인 저장 여부를 사용한다.
API-16·17의 취미 이미지 의미는 바꾸지 않는다.
API-24의 ID ASC·api24-id-v1·size·서명·keyset은 변경하지 않는다.
[Issue #118](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/118)의 display_order·복합 cursor 전환은 계속 별도 작업이다.
이미지 전용 URL은 현재 nullable 확장이므로 기존 행은 종전 취미 이미지가 유지된다.
두 API reader를 함께 배포한 다음 활동 전용 이미지를 채운다.

## 적용·롤백·검증

최신 develop은 V15이며, OPEN PR #121의 V16 예약을 존중해 API-25를 V17 후보로 재배정했다.
현재 V16 미포함 경로의 통과는 V16+V17 통합 검증이 아니다. PR #121이 먼저 병합된 뒤 최신 develop을 안전하게 통합하고
실제 V1~V17 신규 설치·V15→V16→V17 업그레이드·JPA validate·API-16~25/31~32 전체 회귀를 재실행한다.
전용 V17 테스트는 알려진 PR #121 V16 파일의 존재 여부에 맞춰 전체 적용 버전 순서/개수를 정확히 단언한다.
단순 pending=0을 성공으로 인정하거나 마지막 버전만 확인하도록 완화하지 않는다. 이 테스트의 V16 호환성은
실제 V16을 포함한 실행 증거를 대신하지 않는다. 앱 전체의 최신 JPA 매핑 검증은 기존 latest 경로로 별도 유지한다.
다른 migration이 먼저 병합되면 번호를 다시 확인한다. API-25는 그때까지 Draft를 유지한다.
앱 배포 전 확장 schema를 적용해야 하며 기존 migration checksum은 유지한다.
새 제약·컬럼·함수/trigger를 제한된 migration role로 설치할 수 있는지 확인한다.
함수는 SECURITY INVOKER이며 고정 search_path와 schema-qualified 테이블을 사용한다.
앱의 기존 카탈로그 SELECT/공유 잠금 및 승인된 콘텐츠 DML 권한을 유지해야 한다.
구버전 읽기/저장 API는 additive schema에서 동작해야 한다. 문제 시 쓰기를 동결하고 forward fix한다.
새 컬럼 DROP·V17 재편집·repair/outOfOrder 우회·기록 합치기 rollback은 하지 않는다.
JPA validate는 타입만으로 trigger·CHECK·데이터 품질을 보증하지 않으므로 실제 DML·경합·catalog 검증을 병행한다.

테스트 실행 결과는 최종 검증 후 아래에 기록한다. 과거 테스트 수를 이번 결과로 사용하지 않는다.
실제 카탈로그 데이터의 정비 완료 여부는 합성 fixture 테스트로 증명할 수 없다.

## 남은 정책·판정

- 기존 step.title/isRequired와 contentUpdatedAt의 임시 null은 원문 non-null 예시와 차이가 있다.
- description NULL·획득 방식/NULL·주의사항 한 항목 보존은 콘텐츠/FE의 최종 확인이 필요하다.
- COMMON429_1은 미구현 공통 기능이다.
- 운영 백필·이미지 정비·스냅샷/성장 원장·활동 표시 순서는 미실행이다.
- **CONDITIONAL GO**: 기능·확장/무손실 이관의 검토용 Issue/Draft PR은 제안할 수 있으나,
  전체 계약 충족 및 정식 출시/병합 GO는 콘텐츠 정비·nullable 계약 승인 전 보류한다.

## 최종 로컬 검증 결과 (2026-11-07)

PostgreSQL 17.11 / Redis 7.4.12를 전용 임시 컨테이너로 실행했다.
앱 계정 api25_app은 SUPERUSER/CREATEDB/CREATEROLE 권한 없이 DML·시퀀스 권한만 사용했다.
Flyway 테스트는 각 사례마다 별도 DB와 비-superuser migration owner/app role을 생성·정리한다.

| 경로 | 실제 테스트 수 | 실패 / 오류 / 스킵 |
| --- | ---: | --- |
| V1~V15 + API-25 V17 (팀 V16 미포함), Java·HTTP·Flyway | 629 | 0 / 0 / 0 |
| 위 기준의 V3 제외 CI 호환 스키마, Java·HTTP | 615 | 0 / 0 / 0 |

CI 호환 경로는 migration 테스트 14개를 제외한다. 해당 14개는 위 전체 실행에서 별도 검증했으며
실패한 테스트를 제외한 것이 아니다. 두 실행의 XML을 직접 합산해 테스트 실행·실패·오류·스킵을 확인했다.
신규 API-25 30개(동시 편집 회귀 1개 추가), API-24 HTTP 45개·cursor 9개, API-16·17 12개,
API-18~22 HTTP 156개, API-23 22개, API-31·32 10개를 포함한다.
최신 추천 21개·멱등성 7개 및 기존 회원·프로필 이미지·온보딩 등 전체 회귀도 통과했다.

V1~V15 + 예약 V17 신규 설치와 기존 V15→V17 업그레이드, legacy 행의 기존 컬럼·준비물·저장·기록·나무
JSON 대조, title/isRequired/이미지·과거 수정 시각 미추론, 이후 실제 변경 시각/버전,
제한 계정 trigger 실행·신규 생성 version 1·한 트랜잭션 1회 증가·no-op·rollback을 검증했다.
부모·자식 경쟁 편집 중 RR snapshot 일관성, 별도 PostgreSQL 커넥션의 FOR SHARE 대기와 자식 편집,
JPA 생성/수정 generated 값 및 자식 변경 후 refresh, 항목 수에 무관한 SQL 조회 수도 통과했다.
JPA validate, Spotless, Java/테스트 컴파일 및 diff 검사를 통과했다.

이전 로컬 구현 최초 실행은 새 탈퇴 회원 fixture의 withdrawn_at/restorable_until 누락으로 1건 실패했다.
기존 탈퇴 제약에 맞는 fixture로 수정했고 정책·기대값·제약을 완화하지 않았다. 위 표는 V17 재배정 후 새 격리 환경의 재실행 결과다.
로컬 검증 기록은 worktree 밖 tmp/api25-local-validation의 full/ci-results.json과 XML에 보존한다.
PR #121의 V16과 통합한 결과·운영 DB 적용 결과가 아니다. 실제 운영 카탈로그가 정비됐다는 증거도 아니다.

## 변경 파일

- 신규: ActivityDetailController.java, ActivityDetailService.java, ActivityDetailResponse.java.
- 기존 확장: Activity.java, ActivityStep.java, ActivityRepository.java, ActivityStepRepository.java.
- API-24 이미지 연동: StageActivityService.java (정렬·cursor·저장 API 변경 없음).
- 코드: ActivitySuccessCode.java, ActivityErrorCode.java.
- migration: V17__add_activity_content_metadata.sql만 신규.
- 테스트: ActivityDetailIntegrationTests.java 신규, FlywayMigrationTests.java 확장.
- 문서: activity-detail.md 신규, stage-activities.md에 API-25 이미지 연동 명시.

총 15개 파일. 별도 설계 worktree와 기존 V1~V15, 다른 팀원 코드·브랜치를 변경하지 않았다.

추적 Issue: [#122](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/122).
원격 CI·CodeRabbit 결과는 Draft PR에 별도로 기록하며, 성공하더라도 선행 V16 통합·정책 승인 gate를 해제하지 않는다.

원격 첫 Docker CI는 새 ActivityDetailIntegrationTests의 직접 opt-in 선언 누락으로 DB 없는 단위 단계에서 실패했다.
기존 다른 통합 클래스와 동일하게 AUTH_INTEGRATION_TEST=true 조건을 클래스에 선언했다.
DB 없는 test/spotlessCheck/bootJar 경로와 실제 PostgreSQL·Redis 통합 경로를 각각 재실행해 확인한다.
통합 실행에서는 API-25 30개가 모두 실행되고 실패·오류·스킵 0인지 XML로 검증한다. CI 조건을 완화하지 않는다.

두 번째 Docker CI는 PostgreSQL의 20개 연결 한도가 소진된 진단을 남겼다. 새 클래스만의 중복 show-sql=false
TestPropertySource 재정의를 제거하여 기존 API-24·나무 테스트의 동일 설정 컨텍스트/풀을 공유하도록 맞췄다.
실제 ec2 프로필은 이미 show-sql=false다. DB 연결 한도·전역 풀 설정·검증 timeout·assertion을 완화하지 않는다.

