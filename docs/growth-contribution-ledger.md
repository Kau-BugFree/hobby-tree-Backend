# 공통 성장 기여 원장 A1 — 로컬 구현·전환 안내

2026-11-08 KST. 설계는 별도 `tmp/backend-api-26-30-design/docs/activity-record-system-design.md` 17절을 기준으로 했다. 설계 worktree와 API-25 worktree를 수정하지 않았다.

## 기준과 미병합 의존성

- origin/develop: `a25118548abca637597a2e247109eb7106e2fce1`, 실제 최신 migration V17.
- API-25 [PR #123](https://github.com/Kau-BugFree/hobby-tree-Backend/pull/123): OPEN/Draft, HEAD `dbbe0f88eff5642a70d3eac30a4867e25067374b`, V18 미병합.
- 로컬 구현 브랜치 `feat/growth-contribution-ledger`, worktree `tmp/backend-growth-ledger`는 **실제 API-25 HEAD에서 분기한 격리 통합 브랜치**다. API-25 SQL을 가짜 선행 migration으로 복제하지 않았다.
- `V19__add_activity_growth_contributions.sql`은 이 브랜치 안의 **로컬 후보**다. develop의 확정 다음 버전으로 예약·공개한 것이 아니다. PR #123 병합 후 최신 develop/병행 PR을 재확인하고 실제 빈 번호·부모 SHA에서 다시 검증해야 한다.
- 이 작업은 commit/push/Issue·PR 생성·운영 적용을 하지 않는다. V18의 정책 승인·Draft 해제를 대행하지 않는다.

## 구현 범위

- activity_growth_contributions: pair UNIQUE(tree,activity), origin_record UNIQUE, origin/tree/activity 복합 FK, tree/activity FK, source·stage·시각 CHECK, tree/stage 집계 인덱스.
- 기존 기록의 triple UNIQUE를 FK 지원용으로 추가한다. **기존 record pair UNIQUE와 모든 기존 컬럼·CHECK·FK는 유지**한다.
- JPA의 append-only 원장 Entity와 출처 enum, Repository의 origin/pair 조회 및 읽기 전용 `countLegacyMismatches()`를 추가했다. 이번에는 성장 발급/수정 서비스나 자동 동기화 trigger가 없다.
- 기존 ActivityRecordRepository.countGrowthByTreeIds의 쿼리 원천만 원장으로 바꿨다. GrowthCounts alias/의미와 UserHobbyService 호출부·공식·DTO는 동일하다. current activity·lastActivityAt도 기존 쿼리를 유지한다.
- API-26~30·39, 반복 수행·record UNIQUE 해제, snapshot, 단계 재계산, 실제 콘텐츠는 구현하지 않았다.

## 기존 성장 writer 전수 확인

검토 범위는 현재 `src/main/java`, `src/main/resources/db/migration` 전체다. test SQL과 문서는 운영 writer에서 구분했다. 검색식은 `ActivityRecordRepository|new ActivityRecord|growthCounted|growth_counted|growthStageNumber|growth_stage_number|currentStageNumber|current_stage_number`와 `@Modifying|nativeQuery|JdbcTemplate|EntityManager|deleteBy|@Scheduled`의 호출 경로다.

| 코드 경로 | 확인 결과 |
| --- | --- |
| domain/tree/entity/ActivityRecord.java | 보호된 기본 생성자·getter와 매핑만 존재. 성장 setter/factory가 없고 기록 pair UNIQUE 유지 |
| domain/tree/repository/ActivityRecordRepository.java | 애플리케이션 호출자는 UserHobbyService 한 곳. current/last/growth 조회만 호출하며 save/delete/성장 update 호출 없음 |
| domain/tree/service/UserHobbyService.java | API-18·20·22 조회, API-19 심기, API-21 상태 변경. 기여·기록 생성/성장 단계 갱신 없음 |
| domain/tree/entity/UserHobby.java | plant는 기존0단계 초기값, changeStatus는 상태/pausedAt/endedAt만 변경. 성장 writer 아님 |
| domain/experience/entity/ExperienceReservation.java, service/ExperienceService.java | 기여 연결 필드와 조회 기능만 존재. API-39 참여 증명·성장 발급 writer 없음 |
| 기존 V1~V18 | records 정의·제약/FK만 있고 기존 성장 기여를 생성·수정하는 migration DML 없음. V18은 콘텐츠 aggregate만 변경 |
| 신규 V19 후보 | 기존 growth_counted=true를 읽어 원장에 INSERT하는 일회성 백필. records/tree UPDATE·DELETE 없음 |

따라서 **현재 저장소 애플리케이션의 실행 경로에는 기존 성장 생성·수정 writer가 없다.** JpaRepository에 상속된 save/delete가 있다는 사실과 실제 호출을 구분한다. 이 결론은 외부 배치·운영 직접 SQL·수동 이관·다른 미병합 브랜치의 부재를 증명하지 않는다. 실제 전환 전 담당자가 해당 writer를 식별·중지·통제해야 한다. 기존 코드를 삭제하거나 기능 중단 코드를 넣지 않았다.

이는 같은 테이블을 쓰는 기능까지 없다는 뜻은 아니다. 기존 API-19는 나무 INSERT, API-21은 user/tree 잠금 후 상태 UPDATE를 수행한다. migration의 테이블 잠금·FK 검증·ALTER와 경합할 수 있으므로 **이런 row locker/비성장 writer도 적용 창에서 신규 유입 제어·진행 트랜잭션 drain 대상**이다. 임의 중단 코드는 추가하지 않았으며 실제 영향/중지 승인은 배포 담당자의 별도 확인이 필요하다.

원장 전환 후 flags만 바꾸는 구 writer는 지원하지 않는다. 읽기 전용 감사는 불일치를 감지하지만 자동 복구하거나 쓰기를 동기화하지 않는다. 이를 감추는 UNION/fallback이나 신규 자동 mirror trigger를 추가하지 않았다. 미래 API-29/39는 검증된 완료 기록·원장·호환 flags·나무 변경을 같은 트랜잭션에 넣어야 한다.

## 이관 전 감사와 실패 처리

승인된 환경에서 먼저 읽기 전용 계정/동일 snapshot으로 제약과 데이터를 감사한다. 실행 가능한 SELECT 묶음은 `scripts/sql/audit-growth-contribution-source.sql`이며, V18의 READ ONLY/REPEATABLE READ 테스트 세션에서 검증한다. 이번 작업은 실제 RDS에 접속하거나 감사를 실행하지 않았다. 원장이 없어도 실행 가능한 preflight와 적용 후 원장 감사를 구분한다.

신규 migration은 안정화 잠금 이후 **어떤 DDL/백필보다 먼저** 다음을 읽기 검증한다.

1. records pair UNIQUE·PK·tree/activity FK의 존재/유효성, 기존 NOT NULL 유지 및 관련 미검증 제약.
2. 고아 기록, 기여된 비COMPLETED/단계 null·범위 오류, 기여되지 않았는데 저장된 단계, 기여 pair 중복.
3. 저장 stage와 min(5,N/k), 현재 기여 bucket과 N-stage*k의 차이. bigint 계산을 사용하며 k를3으로 하드코딩하지 않는다.

**이번 구현 요청의 '불일치 발견 시 중단'을 적용하여 기존 성장 기준선 불일치도 SQLSTATE23514로 중단한다.** 설계 검토 때 A1에서 원형 보존 후 별도 이행이 가능하다고 본 항목이라도 이번 구현이 자동 승인/교정하지 않는다. 실제 자료와 정책을 확인한 뒤 별도 검토가 필요하며 stage/기여를 이 migration으로 수정하면 안 된다.

다중 IN_PROGRESS·attempt_count>1·미정비 콘텐츠·복원 불가능한 snapshot/응답 자체는 이 A1의 수정 대상이 아니다. 정상 기여 기준선의 fixture에서는 모두 그대로 보존됨을 검증한다. 진행 중1개 partial UNIQUE는 이번에 추가하지 않는다.

## 백필·행별 검증

- growth_counted=true인 원본 행 전체를 복사한다. recordId/treeId/activityId/growthStage가 원장 origin/tree/activity/creditedStage가 된다.
- source=LEGACY_RECORD, creditedAt=null, migratedAt만 실제 복사 시각이다. 과거 finishedAt이나 현재 콘텐츠를 인정 시각/스냅샷으로 사용하지 않는다. 외부 예약에 연결돼 있어도 증명 없이 EXPERIENCE로 추정하지 않는다.
- records의 attempt_count·step_progress·상태·feedback·모든 timestamp 및 tree stage/updatedAt을 전혀 갱신하지 않는다.
- 백필 뒤 FULL JOIN 양방향 행 대조와 단계별 COUNT 양방향 EXCEPT로 누락·추가·오연결을 검사한다. 실패하면 DDL·백필·Flyway history 전체 rollback이다.
- 기존 이름의 미지 원장 테이블이 있으면 실패시킨다. 덮어쓰기·TRUNCATE·무조건 ON CONFLICT 무시는 하지 않는다. Flyway 적용 후 재실행은0개이며, repair/outOfOrder/기존 checksum 변경이 없다.
- 신규 source SELF/EXPERIENCE와 시각 제약은 후속 기록을 위한 구조일 뿐 이번에 해당 출처의 실제 성장 발급 기능을 제공하는 것이 아니다.

## writer/reader 전환과 병합·배포 조건

1. 실제 V18 병합 및 최신 migration 번호 확인. 현 후보 V19를 확정 번호처럼 배포하지 않는다.
2. records 성장/삭제, tree 단계/삭제, k 변경·purge뿐 아니라 API-19/21 등 같은 테이블의 writer/row locker 목록과 통제 담당을 확정한다. 중지·drain을 확인할 수 없으면 실제 전환 NO-GO다.
3. writer/row locker를 drain한 적용 창에서 migration의 테이블 잠금·감사·백필·대조를 완료한다. 원장 도입의 ALTER는 읽기와도 잠깐 충돌할 수 있고, drain되지 않은 row lock과 FK 검증의 경합은 교착 위험도 있다. migrator 세션에 승인된 lock_timeout 등 유한 대기 정책을 정하고 실패 시 전체 rollback/원인 확인 후 재시도한다. 애플리케이션의 전역 timeout이나 인증 정책을 변경하지 않는다.
4. 같은 고정 데이터에서 old N/C와 ledger N/C·API-18/20/22 결과를 대조한 후 모든 reader를 전환한다. Flyway transaction의 잠금은 commit 후 구 writer 재개를 막지 못한다.
5. 원장과 기존 flags를 한 트랜잭션으로 쓰는 승인 writer만 재개한다. 현재 그런 운영 writer가 없다면 미구현 성장 쓰기를 새로 활성화하지 않는다. 수동 flags-only SQL도 재개하면 안 된다.
6. 원장 Repository의 `countLegacyMismatches()`와 다음 읽기 전용 쿼리로 대조한다. mismatch는 자동 교정하지 말고 해당 writer를 중지·조사한다.

```sql
SELECT count(*) AS mismatches
FROM activity_records r
FULL JOIN activity_growth_contributions g ON g.origin_record_id=r.activity_record_id
WHERE (r.growth_counted IS TRUE AND
       (g.contribution_id IS NULL OR g.user_hobby_id IS DISTINCT FROM r.user_hobby_id
        OR g.activity_id IS DISTINCT FROM r.activity_id
        OR g.credited_stage_number IS DISTINCT FROM r.growth_stage_number))
   OR (g.contribution_id IS NOT NULL AND r.growth_counted IS DISTINCT FROM TRUE);
```

이것은 **백필 완료 후** 사용할 감사다. 원장이 없는 V18 DB에서 실행하는 사전 감사와 혼동하지 않는다. 같은 snapshot의 데이터 대조와 무중단 운영 동기화 보장은 다른 개념이다.

## 테스트와 근거

새 PostgreSQL17.11·Redis7.4.12 전용 컨테이너와 제한 앱 계정으로 실행했다. 운영 데이터를 복제하지 않았다. fixtures는 TEST ONLY/ledger-fixture 접두사이며 실제 콘텐츠가 아니다. 검증 환경·로그·JUnit XML은 `tmp/growth-ledger-validation`에 보존한다.

| 경로 | 실제 결과 |
| --- | --- |
| V1~V19 후보 전체 Java·HTTP·Flyway | 664개, 실패0/오류0/스킵0 |
| V3 제외 CI 호환 | 642개, 실패0/오류0/스킵0. Flyway22개는 위 전체 경로에서 별도 실행 |
| 신규 원장 통합 | 9개 포함: HTTP18/20/22 동일성·JPA identity/unknown time·구 writer 감지·양쪽 commit/rollback·독립 connection 경쟁 |
| Flyway | 22개 포함, CI source/report 검증기 통과. 신규 원장 시나리오8개 |
| 기존 API-18/20/22 HTTP | 22/28/25개 통과. 기대값 변경 없이 fixture만 명시적으로 원장도 생성 |
| 기존 API-19/21 HTTP | 28/53개 통과. 생산 코드 변경 없음 |
| Spotless·컴파일·diff | Spotless Apply/Check·Java/테스트 컴파일·git diff --check 통과 |

원장 고유성 경쟁은 실제 별도 connection과 PostgreSQL lock wait를 확인한다. 첫 insert commit 시 후행0개, rollback 시 후행1개이며 최종 기여1개다. 아직 유지 중인 record pair UNIQUE를 무력화해 서로 다른 반복 record를 만들지는 않는다. 해당 API-29/39 경쟁은 후속 단계의 테스트다.

이관 테스트는 원래 컬럼 전체 JSON·저장/예약/콘텐츠/나무 데이터를 전후 대조하고, unknown times·attempt_count·다중 진행을 보존한다. schema-drift fixture는 격리 DB에서 실패·무변경을 확인하는 용도이며 기존 migration을 고치거나 정상 제약을 꺼서 성공시키지 않는다. 제한 역할의 직접 INSERT·제약·DDL 거절·owner purge cascade도 검증했다.

## 남은 위험

API-25 미병합 의존성/번호 확정, 실제 데이터의 기준선 불일치 여부, 저장소 밖 writer·전환 통제는 미검증이다. 콘텐츠/legacy 응답 정책·진행/완료/성장 writer는 후속 범위다. 기술 로컬 검증과 실제 이관 승인은 구분하며 **CONDITIONAL GO**로 보고한다. API-25 Draft·기존 worktree·기존 migration을 보존하고 운영 적용은 하지 않는다.
