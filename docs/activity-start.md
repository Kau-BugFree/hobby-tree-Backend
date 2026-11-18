# API-26 활동 시작·이어하기

Issue #128. 선행 원장 PR #136 / Issue #227의 V27 뒤에서 개발한다.
이 문서는 로컬 후보 계약이며 운영 공개·migration 적용 완료를 뜻하지 않는다.
선행 PR 병합 전에는 이 브랜치를 develop 대상 PR에 중복 게시하지 않는다.

## 계약

`POST /api/v1/user-hobbies/{userHobbyId}/activity-records`, JWT, `{ "activityId": 양의 ID }`.

- 신규: HTTP201 / RECORD201_1, 기존과 동일한 ApiResponse 형식.
- 동일 활동 진행 중: HTTP200 / RECORD200_1. 같은 recordId·startedAt·콘텐츠 버전·기록 버전·현재 체크를 반환한다.
- 다른 활동 진행 중: HTTP409 / RECORD409_1.
- 본인 나무404, 비ACTIVE 나무 TREE409_4, 취미 불일치 RECORD400_1, 단계 잠금 ACTIVITY403_1을 사용한다.
- 미등록·비공개·미지원·실행 단계 미등록·제목/필수 여부 미정비·필수0개는 ACTIVITY404_1로 시작을 차단한다.
- 진행 중인 본인 스냅샷은 live 카탈로그가 수정·숨김된 뒤에도 재사용한다. 회원/나무 ACTIVE 검사는 계속 수행한다.

응답: recordId, userHobbyId, activityId, status, contentVersion, completedStepIds,
progressPercent, version, startedAt, reused. 초기 version=1, required 진행률0이며 타이머나 모의 데이터는 없다.
선택 단계 완료는 완료 목록에 포함하지만 필수 진행률 분자/분모에 넣지 않는다.
활동 재시작은 새 기록이며 시작 자체는 성장 원장을 추가하거나 나무 단계를 변경하지 않는다.

## 사용자 확정 명세 보완 — 2026-11-18

시작 스냅샷이 없는 과거 진행 기록은 HTTP409 **RECORD409_5**로 시작·이어하기를 차단한다.
ID·step_progress·attempt_count·과거 시각을 보존하며 현재 콘텐츠로 과거 스냅샷을 만들지 않는다.
contentVersion/진행률/시작 시각을 추정하거나 본인 기록을404로 숨기지 않는다.
실제 정비·별도 종료는 승인된 데이터/정책 작업이며 이 API가 자동 수행하지 않는다.

## 트랜잭션·스냅샷

user 쓰기 잠금 → 소유 tree 쓰기 잠금 → 진행 record 쓰기 잠금 순서를 기존 API-19/21과 맞춘다.
신규 시작에서는 hobby → stage → activity 공유 잠금을 확보하고 소속을 재검증한 뒤 새 SELECT로 내용을 읽는다.
활동/취미/준비물/주의사항은 API-25 매핑을 사용하며 개인 isSaved와 live 실행 단계 배열을 JSON에 중복 저장하지 않는다.
실행 단계의 ID·순서·제목·설명·필수 여부·이미지를 별도 불변 행으로 복사한다. live step FK는 만들지 않는다.

자식 편집의 V18 parent-touch가 activity 공유 잠금과 경쟁하므로, 이전 버전+이전 내용 또는
편집 commit 뒤 새 버전+새 내용을 캡처한다. 여러 사용자는 카탈로그 공유 잠금을 같이 가질 수 있다.
관계가 잠금 대기 중 이동하면 시작 불가로 중단하며 다른 부모의 데이터를 섞지 않는다.
snapshot 저장·검증과 record 생성은 같은 transaction이고 commit 성공 뒤에만201을 반환한다.

## V28 후보와 보존

- 기존 기록은 format1로 남기고 record_version/elapsed_seconds를 null로 둔다. 기존 값은 갱신하지 않는다.
- 새 자체 기록 format2는 실제 startedAt, version1, elapsedSeconds0으로 생성한다.
- 기존 record pair UNIQUE 대신 나무당 IN_PROGRESS1개와 기여 flag origin pair1개를 보장한다.
- 이미 다중 IN_PROGRESS가 있으면 DDL 전에23514로 중단한다. 자동 삭제/종료/legacy 예외 index는 없다.
- 콘텐츠와 단계 membership·메타데이터는 DB trigger로 불변이며 단계 is_completed만 변경할 수 있다.
- format2의 snapshot 및 필수 단계 존재는 commit 시 지연 constraint trigger로 검사한다.
- 나무/회원 purge의 기존 record cascade는 유지한다. 원본 실행 단계 삭제는 snapshot에 전파되지 않는다.
- **활동 자체의 hard delete는 기존 record/원장 FK로 계속 차단한다.** 활동 물리 삭제 이후 조회까지 지원한다고 주장하지 않는다.
  물리 삭제·카탈로그 참조 분리는 API-27/콘텐츠 운영 후속 검토 사항이다.

## 기존 체험 인정과 반복 기록

pair당 record1개를 가정하던 API-39 조회를 원장 origin과 최신 기록 조회로 분리한다.
이미 기여한 활동의 새 자체 진행은 원장을 다시 만들지 않고 그대로 유지한다.
새 format2 자체 진행 중 체험 기여가 발생하면 별도 체험 완료 기록에 기여하고 자체 snapshot/진행을 덮어쓰지 않는다.
기존 format1에 대한 참여 인정 동작과 외부 제공자 검증 미연동503은 유지한다.

## 검증·공개 경계

fixture는 TEST ONLY이며 실제 취미 콘텐츠로 반입하지 않는다.
HTTP 권한·요청 오류·상태/단계/취미·미정비 거절·이어하기·반복 수행·동시 시작·rollback,
불변 snapshot·원본 단계 변경/삭제·독립 connection의 편집/시작 잠금 경쟁을 검증한다.
실제 V27→V28 무손실 이행과 다중 진행 중단·전체 DDL rollback, 현재 체험·나무 조회도 확인한다.
새 통합 테스트 컨텍스트가 누적되면 유휴 Hikari 연결이 테스트 DB 상한에 도달하므로,
AUTH_INTEGRATION_TEST 실행에만 캐시 최대6개를 적용한다. 각 풀의 동시 처리 용량·DB 상한·운영 설정은 변경하지 않는다.
[Spring 공식 캐시 설정](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)에 따른
JVM 속성으로 오래된 컨텍스트와 연결을 닫으며 테스트나 assertion을 제거하지 않는다.
API-20의 기존 다중 IN_PROGRESS fixture는 새 제약과 맞는 단일 진행+종료 기록 사례로 전환하며,
과거 다중 진행 자체는 migration 거절 사례에서 별도로 검증한다.

API-27~30, 진행 저장·완료/성장·최초 종료 재전송 응답은 이번 endpoint에 추정 추가하지 않는다.
별도 취소 API가 없으므로 전체 시작→진행→종료 수명주기를 구현·검증하기 전 시작만 운영 공개하지 않는다.
V28 번호는 선행 V27 병합과 최신 develop/열린 PR 확인 후 확정한다.
AWS·운영 설정·배포·실제 DB 읽기/쓰기·유료 모델 호출은 이번 로컬 개발에 포함하지 않는다.
