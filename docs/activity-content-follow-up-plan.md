# API-25: V18 통합·후속 정책 추적 계획

확인일: 2026-11-08 (Asia/Seoul). **CONDITIONAL GO / Draft 유지·현재 병합 보류.**

## 현재 확인 사실

- 최신 develop: a25118548abca637597a2e247109eb7106e2fce1, 실제 최신 migration V17.
- 통합 전 API-25 HEAD d9181798bf28db00a9b797f924e0b836be86c9b1 및 미커밋 원본 문서를 별도 백업했다.
- [PR #123](https://github.com/Kau-BugFree/hobby-tree-Backend/pull/123): Draft. API-25는 V18__add_activity_content_metadata.sql로 재배정했다.
- [PR #125](https://github.com/Kau-BugFree/hobby-tree-Backend/pull/125)는 MERGED다. 실제 V17__add_experience_detail_fields.sql을 develop 이력 그대로 통합했다.
- 이전 두 V17의 버전 충돌은 파일 재배정으로 해소했다. V18 공개 예약이 없음을 확인했고 SQL 본문의 해시를 유지했다.
- 실제 V1~V18 및 V15→V16→V17→V18 검증은 이번 작업의 새 결과로 기록하며 과거 수치를 재사용하지 않는다.
- 팀원 PR 승인·병합·파일 변경을 대신하지 않는다. repair/outOfOrder, 가짜 선행 migration, 미병합 팀원 SQL의 임의 적용을 하지 않는다.

## PR #125 읽기 전용 검토

확인한 팀원 V17은 experiences.materials(JSONB, NOT NULL DEFAULT []), cancellation_policy_url(TEXT NULL),
experience_sessions.capacity(INTEGER NULL, >0 CHECK)를 추가하고, 기존 회차 정원을 부모 experience 정원에서 백필한다.
API-25의 activities/activity_steps 확장과 대상 테이블은 다르지만 **동일 버전 충돌은 실제로 존재**한다.
원래 문서 작성 시에는 미병합 SQL의 정적 검토였다. 이제 병합된 실제 V17 위에 별도 체험·회차 fixture를 두고
API-25 V18 전후 JSON 대조를 검증한다. 팀원 SQL을 임의 복사·수정하지 않고 develop의 원본 이력을 통합했다.

## 병합 후 실행 순서

1. PR #125가 실제 MERGED인지와 최신 origin/develop의 SHA·V*.sql·다른 공개 PR의 번호 예약을 다시 확인한다.
2. 현재 작업 상태를 기록/백업하고 기존 API-25 브랜치에서 최신 develop을 일반 merge한다.
   다른 worktree를 변경하거나 reset/clean/강제 rebase하지 않는다.
3. 실제 최대 버전이 V17이고 V18의 다른 예약이 없을 때 API-25 파일을 V18__add_activity_content_metadata.sql로 재배정한다.
   추가 migration이 먼저 들어오면 빈 번호를 다시 판단한다. V1~V16과 팀원 V17은 그대로 둔다.
4. 재배정 전후 API-25 SQL 본문의 해시를 대조하고, 테스트의 자체 migration target/이름/기대 순서 및 문서 참조를 수정한다.
   과거에 실행한 V17(API-25) 수치를 V18 결과로 바꿔 쓰지 않는다.
5. 기존에 API-25 V17이 적용된 테스트 DB에 팀원 V17을 덮어쓰지 않는다. 새 격리 PostgreSQL 17·Redis에서 검증한다.
6. 실제 통합 테스트 통과 후 기존 PR #123에만 커밋·push하고 최신 HEAD 기준 CI·CodeRabbit과 스레드를 확인한다.
   Draft 해제·merge·AWS/ECR/RDS 작업은 하지 않는다.

## 필요한 통합 검증

- 실제 V1~V18 신규 설치 및 V16 → 팀원 V17 → API-25 V18 단계별 업그레이드.
  원본 Flyway history/checksum을 유지하고 각 단계 validate·재실행 0개를 확인한다.
- V16에 기존 체험·회차·정원을 준비한 뒤 팀원 V17의 materials 기본값/회차 capacity 백필을 검증한다.
  V17 적용 직후 체험·회차·준비물·정책 URL의 실제 값을 스냅샷으로 기록하고 V18 후 동일함을 대조한다.
- 기존 활동·준비물·실행 단계·저장·기록·성장 기여·나무 데이터의 무손실 대조.
  미확정 제목/필수 여부/역사적 시각/획득 방식을 추론하지 않는다.
- JPA validate·제한 DB 역할, 콘텐츠 trigger INSERT/UPDATE/DELETE/no-op/rollback·동일 tx 1회 증가·동시 편집·공유 잠금·상속 이미지/실내외.
- API-24·25·체험 상세(API-36) 및 기존 API-16~25/31~32, 최신 체험 목록/회원/온보딩 등 전체 Java·HTTP·Flyway 회귀.
- V3 제외 CI 호환 스키마, Docker amd64/PostgreSQL, Flyway, Spotless·컴파일·diff 검사.
  opt-in 통합 테스트는 실제 환경에서 실행하고 실패·오류·스킵을 확인한다. 검증 조건/연결 한도를 완화하지 않는다.
- 실행별 실제 XML 수치·기준 SHA·최종 HEAD·스키마 경로를 별도로 기록한다. 이전 639/625는 새 팀원 V17과의 공존 결과가 아니다.
  현재 V18 결과는 activity-detail.md의 PR #125 통합 후 두 행과 PR #123의 최종 검증 절에 기록한다.

## 유지할 임시 콘텐츠 정책

| 항목 | 현재 의미 |
| --- | --- |
| step.title=null | 미정비 실행 단계 제목. instruction에서 추론하지 않음 |
| step.isRequired=null | 미확정. false 또는 선택 단계로 간주하지 않음 |
| contentUpdatedAt=null | 역사적 aggregate 수정 시각 불명. 응답 시각/부모 updated_at으로 채우지 않음 |
| description | detailed_description 원문 또는 null. short_description 자동 대체 없음 |
| acquisitionType | 기존 문자열 또는 null. 모름을 NONE으로 바꾸지 않음 |
| cautions | 원문 전체 문자열 한 항목 또는 빈 배열. 자동 문장 분해 없음 |
| API-26 신규 시작 | 미정비 실행 단계 및 필수 단계 0개 콘텐츠 차단 예정. 이번 작업에서 선행 구현하지 않음 |

아직 팀의 최종 승인을 받은 정식 non-null 계약이 아니다.
후속 Issue를 만든다는 사실만으로 임시 계약 승인이나 PR #123의 병합 허가를 대신할 수 없다.

## 후속 Issue 등록안 — 이번에는 계획만 작성

등록 전 저장소 전체 상태에서 중복을 재확인한다. 이번 제목 검색에서는 별도 콘텐츠 정비/nullable Issue는 확인되지 않았으며,
이미지·콘텐츠 계약의 기존 추적 건 [#118](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/118)은 재사용한다.
Issue 제목·템플릿·라벨·담당자는 생성 시 기존 팀 규칙과 담당자 합의로 확정한다.
아래 목록은 **실제 새 Issue를 생성하거나 다른 팀원에게 배정한 결과가 아니다.**

### F1. [CHORE] 기존 활동 실행 단계 제목·필수 여부 콘텐츠 정비

- 콘텐츠 담당자의 stepId별 제목/필수 여부 근거를 확보한다. 운영 데이터가 없다고 가정하지 않고 승인된 환경에서 읽기 전용 감사를 먼저 한다.
- 기존 stepId/순서/instruction/기록을 보존한다. 추론 제목·일괄 true/false·가짜 과거 시각·임의 완료 이력을 만들지 않는다.
- 완료 조건: 대상/미정비 수·승인자·근거 매핑·적용 범위·검증 결과를 남기고 미제공 항목을 숨기지 않는다.
- 의존성: F2의 의미 승인. 정비 SQL·실제 RDS 적용은 별도 승인 작업이다.

### F2. [DOCS] API-24·25 nullable 콘텐츠 계약 및 FE 처리 승인

- title/isRequired/contentUpdatedAt, description/quantity/acquisitionType/이미지의 타입·null 의미·표시·오류/제한 정책을 명시한다.
- cautions의 원문 전체 한 항목 보존과 향후 여러 항목 저작 방식, 상세/요약 본문 구분을 확인한다.
- 완료 조건: 기획·콘텐츠·FE·BE의 합의 내용과 승인 이력, 노션 수정 위치, null/빈 배열 렌더링 검증을 기록한다.
- 별도 Issue로 추적해도 현재 미승인이 자동 해소되지는 않는다. PR #123의 명시적 병합 gate로 남긴다.

### F3. [FEAT] API-26 시작 가능 콘텐츠 검증과 스냅샷 조건

- 신규 시작에서 미정비 title/isRequired를 거절하고, 확정된 필수 단계 수가 0인 콘텐츠도 차단한다.
- null을 false로 간주해 분모를 줄이거나 progressPercent를 임의로 0/100으로 채워 시작시키지 않는다.
- 미리보기(API-24·25)와 실제 시작 가능성을 분리한다. 소유권·ACTIVE·성장 단계·공개 여부 및 기존 진행 기록 재사용 계약과 함께 정리한다.
- 완료 조건: 시작 당시 불변 콘텐츠/필수 단계·버전의 원자적 저장, 독립 커넥션 편집 경쟁·미정비/0필수/허용 콘텐츠 HTTP 테스트.
- 의존성: F2·F6, 필요한 snapshot/record schema 별도 설계. 오류 코드는 API-26 원문 범위에서 확인하고 임의 확정하지 않는다.

### F4. [CHORE] 콘텐츠 정비 후 공개 콘텐츠 정합성 제약 강화

- F1·F2 결과와 실제 누락/기타 값 감사를 근거로 신규 migration을 설계한다.
- Draft 미완성 데이터와 공개 콘텐츠 조건을 구분한다. 데이터 근거 없이 전역 NOT NULL·enum CHECK를 즉시 강제하지 않는다.
- 자식/부모에 걸친 조건을 단순 cross-row CHECK로 표현하지 않는다. publication 서비스·최종 상태 검증의 책임을 결정한다.
- 완료 조건: 공개 정합성 검증, 전체 신규/업그레이드·제한 역할·기존 앱 호환·forward-fix 계획·데이터 무손실 테스트.
- 기존 migration 수정·역사적 시각 위조·정비되지 않은 행의 자동 비공개/삭제는 하지 않는다.

### F5. [FEAT] 취미·활동 이미지 자산 관리 및 스냅샷 보존 규약

- #118의 활동 이미지/FE null/콘텐츠 계약 항목을 연결하고 중복 구현을 피한다.
  #118의 display_order·복합 cursor 전환은 이 이미지 관리 이슈와 구분한다.
- 활동 이미지 → 취미 이미지 → null을 유지하면서 자산 소유권·URL/key·업로드/교체·fallback·불변 자산명·삭제/보존을 설계한다.
- URL 복사만으로 파일이 보존되지는 않으므로 과거 활동 snapshot 이미지 보존 정책을 정한다.
- 완료 조건: API-24·25 공동 매핑·부모 fallback 변화의 버전 전파·삭제/교체·권한·FE 표시 회귀.
- 실제 AWS/S3/CDN 변경은 이 계획의 실행이 아니며 별도 승인 범위다.

### F6. [DOCS] 콘텐츠 편집·버전 증가·복원 운영 규약

- baseline=1과 이후 버전, 트랜잭션당 증가, no-op/rollback 및 DB-generated 값의 JPA refresh 기준을 명시한다.
- hobby → activity → 자식 및 다중 부모 ID 순서 등 승인된 writer의 사전 잠금 규약, 관계 이동·배치 정비·교착/timeout 처리를 정한다.
- 직접 SQL/TRUNCATE/trigger 비활성화의 허용 경계를 정한다. 논리 복원·클러스터 이동 시 내부 xid8 토큰과 카운터를 안전하게 관리하는 절차를 실제로 설계·검증한다.
- 완료 조건: 편집·시작 snapshot 경쟁, 다중 행 정비/rollback, 상속 이미지/실내외 전파, 제한 역할·복원 리허설 검증과 운영 책임자 승인.
- 버전·시각을 임의로 초기화하는 운영 SQL을 이 문서에서 실행하지 않는다.

## 등록·연결 시점과 병합 gate

1. PR #123 병합 직전에는 F1~F6의 실제 등록 또는 기존 Issue 재사용 여부를 확정하고 URL을 PR #123·Issue #122에 연결한다.
2. 각 Issue에 담당자·승인 주체·의존성·완료 조건을 남긴다. #122가 Closes로 닫혀도 미완료 항목의 추적은 유지한다.
3. nullable/필수 단계 정책은 별도 Issue 이동만으로 완료 처리하지 않는다. 승인 전 Draft/병합 보류를 유지한다.
4. 실제 팀원 V17 통합·API-25 재배정·로컬/원격 재검증이 완료된 뒤에도 위 승인과 SQL/데이터 보존 검토를 다시 확인한다.

## 이행 기록과 범위

최초 단계에서는 선행 PR 미병합을 확인하고 상태 추적·이 계획 작성만 수행했다. 후속 승인에 따라 원본 계획을 백업한 뒤
PR #125가 병합된 최신 develop을 일반 merge하고 API-25만 V18로 변경해 새 격리 환경에서 검증한다.
미승인 콘텐츠 정책과 F1~F6 계획은 유지하며, 실제 새 후속 Issue 생성은 아직 하지 않았다.
다른 팀원 PR 승인·병합·수정, Draft 해제, PR #123 병합, AWS/ECR/실제 RDS 작업은 하지 않는다.

