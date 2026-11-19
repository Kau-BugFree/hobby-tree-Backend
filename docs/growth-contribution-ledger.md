# 성장 기여 원장 — API-26 선행 기반

후속 API-50/V31은 [기록 삭제 규약](activity-record-deletion.md)에 따라 활성 기여 취소·원본 참조 이전을 지원한다.
과거 인정 단계·출처·시각과 최초 원본은 감사에 보존하고, 현재 집계는 progress_stage_number가 있으면 사용한다.
아래 V27 전환 규약은 당시 이관 범위이며 API-50의 승인된 삭제를 임의 정비/원장 초기화로 대신하지 않는다.

Issue #227 / 기존 Draft PR #136. 기준 develop `6dd336bbd91ba8a6f7d76a444aff38a137b9251e`.
최종 원격·로컬 검증 수치는 PR 본문과 해당 SHA의 결과를 기준으로 한다. 운영 적용은 하지 않았다.

## 목적과 범위

`activity_records`의 반복 수행을 이후 API-26~30에서 지원하기 전에 고유 성장 기여를
`activity_growth_contributions`로 분리한다. API-18·20·22의 집계 계약·공식과 기존 기록은 유지한다.
이 PR은 API-26 endpoint, 반복 기록, snapshot이나 진행·완료 API를 공개하지 않는다.

이전 원장 후보 V19는 미병합 상태였고, 이후 develop의 실제 V19~V26이 적용됐다.
이번 후보는 **V27__add_activity_growth_contributions.sql**이다. V1~V26 원문은 수정하지 않는다.
기존 공개 작업 브랜치 `feat/growth-contribution-ledger`와 PR #136을 재사용한다.
원본 head `8244ea9`와 최신 develop 통합 이력은 재작성하지 않는다.

## 원장과 무손실 이관

- UNIQUE(user_hobby_id, activity_id): 한 나무에서 같은 활동의 성장 기여는 한 번이다.
- UNIQUE(origin_record_id), origin/tree/activity 복합 FK: 실제 기여 원본과 같은 나무·활동을 보장한다.
- 출처는 LEGACY_RECORD / SELF / EXPERIENCE다. 역사적 기여를 SELF/EXPERIENCE로 추정하지 않는다.
- 기존 growth_counted=true만 원본 ID·활동·기여 단계 그대로 백필한다.
- 역사적 인정 시각은 credited_at=null, 실제 이관 시각만 migrated_at으로 기록한다.
- 기존 record ID, attempt_count, step_progress, 상태·시간·feedback, 나무 단계·updated_at을 변경하지 않는다.
- 기존 records pair UNIQUE·활동 FK는 이번 단계에서 유지한다. origin 복합 FK 지원용 triple UNIQUE만 추가한다.

원본 제약/NOT NULL/유효 FK, 기여 중복·상태·단계와 나무 성장 기준선을 **DDL 전에** 감사한다.
불일치하면 SQLSTATE23514로 중단하며 단계·flags·과거 내용을 자동 보정하지 않는다.
백필 뒤에는 원본↔원장 양방향 행 대조와 단계별 COUNT 양방향 EXCEPT를 확인한다.
실패 시 DDL·백필·Flyway history 전체가 같은 트랜잭션에서 rollback된다.
repair/clean/outOfOrder, 기존 원장 덮어쓰기나 자동 mirror trigger는 사용하지 않는다.

## 현재 writer·reader의 같은 트랜잭션 유지

과거 PR을 작성할 당시와 달리 현재 develop에는 API-39의 `ExperienceAttendanceService`가 있다.
기여 writer가 없다는 과거 판단을 이번 기준으로 재사용하지 않는다.

- API-18·20·22는 기존 `countGrowthByTreeIds` projection/alias를 유지하면서 원장을 집계한다.
- API-39는 기존 나무 잠금과 중복 기준을 유지한다.
- 신규 기여 시 기록·EXPERIENCE 원장·나무 단계·예약 확인을 **한 트랜잭션**에 저장한다.
- 이미 기여한 활동은 같은 원본을 재사용해 중복 성장·중복 원장을 만들지 않는다.
- 원장과 호환 flags/원본/단계가 불일치하면 writer가 중단하며 요청 중에 고치지 않는다.
- 진행 중인 기존 기록을 인정하는 현재 동작은 step_progress·attempt_count를 보존한다.
- 외부 참여 증명 검증 미연동은 그대로다. HTTP의 ATTENDANCE503_1을 우회하거나 제공자 연동이 완료됐다고 주장하지 않는다.

실제 저장소 밖 배치·관리 SQL의 부재는 확인하지 않았다.
`countLegacyMismatches()`와 `scripts/sql/audit-growth-contribution-source.sql`은 읽기 감사이며 자동 복구 도구가 아니다.
후속 API-29도 원장과 호환 필드를 한 트랜잭션으로 써야 한다.

## 운영 전환 조건과 복구 경계

이번 작업은 로컬 코드·격리 검증·GitHub 리뷰 범위다. 실제 RDS 감사·쓰기·배포는 별도 승인 대상이다.

1. 최신 develop·열린 migration PR을 다시 확인하고 V27 번호 충돌이 없는지 확인한다.
2. 승인된 환경에서 원본 제약·기여 기준선·외부 writer를 읽기 감사한다. 불일치 시 적용하지 않는다.
3. API-19/21 및 API-39, 배치·직접 SQL 등 관련 writer/row locker를 통제하고 트랜잭션을 drain한다.
4. 현재 main 배포 경로의 앱 fence·snapshot·유한 timeout 아래에서 migration과 JPA 검증을 수행한다.
5. 원본/원장 행·단계별 집계와 API-18·20·22 결과를 대조하고 같은 트랜잭션 writer만 재개한다.

이 migration의 표 잠금은 commit 뒤 구 writer의 재개를 막지 못한다. flags-only writer 재개는 지원하지 않는다.
실패 시 원본을 수정하거나 state/history를 삭제해 재시도하지 않는다. 스키마 적용 뒤 실패는 기존 배포 도구의
격리·실패 기록 보존·복구 검토 절차를 따른다. 자동 DB 다운그레이드는 없다.
기존 앱으로의 호환 복귀는 쓰기를 중지하고 원장↔flags의 일치를 검증한 경우에만 별도 검토한다.
원장·기록 삭제나 flags 자동 재작성으로 되돌리지 않는다.

## 격리 검증과 남은 범위

테스트 fixture는 TEST ONLY/ledger-fixture이며 실제 취미 콘텐츠로 반입하지 않는다.
새 PostgreSQL17·Redis, 제한 앱 역할 및 테스트 전용 인증을 사용한다.
전체 V1~V27 신규 설치, 실제 V17→V18→V26→V27과 V26→V27 업그레이드,
checksum·validate·재실행0, 양방향 백필·기록 무손실·제약·권한·잠금 timeout·DDL rollback을 검증한다.
원장 고유성은 실제 별도 connection의 commit/rollback 경쟁으로 확인한다.
API-39는 중복 인정·기존 진행 보존·동시 요청·원장/기록/단계/예약 rollback 및 재시도를 확인한다.
공개 HTTP의 제공자 미연동 제한도 유지한다.

CI 초기화만 각 migration을 `psql --single-transaction`으로 실행해 LOCK/audit/backfill 원자성을 맞춘다.
최신 develop의 V3 포함 및 runtime 검증을 유지하며 과거의 고정 table-count 검사를 복원하지 않는다.

과거 V18/V19 후보의 664/642개 결과는 이 SHA의 증거로 사용하지 않는다.
현재 검증 결과는 PR에 새로 기록한다. CodeRabbit 할당량 상황에서는 사용자 기준대로 Codex 재리뷰를 사용한다.

API-26 #128은 이 기반 병합 이후 시작·이어하기·불변 snapshot과 필요한 기록 schema를 별도로 다룬다.
legacy snapshot 부재와 API-27~30 공개 수명주기 조건, 실제 콘텐츠 정비 #126·nullable 계약 #127·편집 규약 #130은
이 PR의 병합으로 완료 처리하지 않는다. 시작만 가능한 운영 출시도 이번 범위에 포함하지 않는다.
