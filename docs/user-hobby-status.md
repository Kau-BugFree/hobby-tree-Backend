# 취미 나무 상태 변경 (API-21)

계약 우선순위: 노션 API 명세를 최우선으로 적용한다. 원본 첨부는
HobbyTree_API_본문_49개.md API-21이며, 2026-11-07 사용자 확정 시간 정책을 함께 적용했다.
기존 구조와 충돌하면 응답 필드를 제거하지 않고 필요한 코드·DB 변경과 의존성을 명시한다.

PATCH /api/v1/user-hobbies/{userHobbyId}/status, 본문 {"status":"PAUSED"}.
JWT와 온보딩 완료 ACTIVE 회원이 본인 나무만 변경한다. HTTP 200 / TREE200_3이다.
응답 6개 필드 userHobbyId, treeStatus, currentStageNumber, pausedAt, endedAt, updatedAt을 유지한다.

## 상태와 시간

| 현재 → 요청 | 결과 | 전환 후 시간 필드 |
| --- | --- | --- |
| ACTIVE → PAUSED | 성공 | pausedAt=일시정지 진입 시각, endedAt=null |
| ACTIVE → ENDED | 성공 | pausedAt=null, endedAt=종료 진입 시각 |
| PAUSED → ACTIVE | 성공 | pausedAt=null, endedAt=null |
| PAUSED → ENDED | 성공 | pausedAt=null, endedAt=종료 진입 시각 |
| ENDED → ACTIVE | 슬롯 확인 후 성공 | pausedAt=null, endedAt=null |
| ENDED → PAUSED | HTTP 409 / TREE409_3 | 변경 없음 |
| 같은 상태 → 같은 상태 | 성공 | DB UPDATE 없이 모든 저장 값·시각 유지 |

시간 필드는 현재 상태 진입을 표현한다. 종료할 때 pausedAt을 지우는 이유는 현재 상태와 관계없는
과거 상태 시각을 남겨 이력처럼 해석하게 하지 않기 위해서다. 이는 사용자 확정 정책이다.
이력을 만들거나 과거 이벤트를 추정하지 않는다. 기존 불완전 시간 데이터도 동일 상태 요청에서는
보정하지 않고, 새 전환에서만 위 규칙을 적용한다.
성장 단계 0\~5, 심은 시각, 활동·성장 기여·IN_PROGRESS 기록의 모든 필드는 보존한다.
API-26\~29의 ACTIVE 상태 수정·완료 제한은 후속 구현 범위다.

## updatedAt 정식 저장과 백필

V11은 user_hobbies.updated_at TIMESTAMPTZ NOT NULL을 추가한다.
updatedAt은 저장된 나무 데이터의 마지막 수정 시각이다. 응답 생성 시각을 사용하지 않는다.
INSERT의 DB 기본값은 clock_timestamp()다. BEFORE UPDATE 트리거는 updated_at을 제외한 행 데이터가
실제로 달라진 경우에만 clock_timestamp()로 갱신한다. 같은 값이나 updated_at만 임의 변경하는
SQL에서는 기존 시각을 유지한다. API의 동일 상태 요청 자체는 UPDATE를 실행하지 않는다.

JPA는 @Generated(INSERT, UPDATE)로 DB 생성값을 읽고, 상태 변경 flush 후 실제 저장값을 응답한다.
기존 @UpdateTimestamp(DB)의 일반적인 CURRENT_TIMESTAMP는 트랜잭션 시작 시각이라 잠금 대기
이전 시각이 될 수 있어 이 컬럼은 실제 쓰기 시각을 사용한다. 앱에서 updatedAt을 따로 설정하지 않는다.
트리거는 상태 외 성장 단계 등 실제 나무 데이터 변경에도 적용된다. SECURITY INVOKER이며 app
계정의 기존 DML 권한으로 동작한다. 행 수정 시각이며 커밋 시각이나 감사 이력은 아니다.

기존 행은 GREATEST(planted_at, paused_at, ended_at)을 초기 기준값으로 저장한다.
planted_at이 NOT NULL이므로 나머지가 null이어도 값이 있다. 이는 남아 있는 데이터에 근거한
초기화이며 **과거 실제 최종 수정 시각을 복원한 값이 아니다**.
ACTIVE 재개에서 시각을 지운 나무는 예전 planted_at만 남을 수 있다. 그런 재개 이력이나 기록되지
않은 단계 변경 시각은 복원할 수 없다. 마이그레이션 시각을 과거 이벤트로 꾸미지 않는다.
기존 상태·pausedAt·endedAt·활동 기록은 백필에서 수정하지 않는다.
V11 이후 실제 수정부터는 DB가 기록한 마지막 수정 시각을 제공한다.

## V10/V11 의존성과 적용 조건

- 기준 develop: 4bcf820c0a8c5cef44f383e24d4d9a5f0dc8ac2b (2026-11-07 재확인).
  최초 통합한 6fde520 이후 검토 도중 병합된 API-17 PR #96도 충돌 없이 반영했다.
- [PR #93](https://github.com/Kau-BugFree/hobby-tree-Backend/pull/93)은 병합 완료다.
  프로필 이미지 API·UserService 변경 및 정식 V10__add_profile_image_uploads.sql을 모두 반영했다.
- API-21 추가 SQL은 V11__add_user_hobby_updated_at.sql 하나다. V10은 develop에서 상속하며
  이번 기능 커밋에 중복 추가·수정하지 않는다.
- PR #93 선행 병합 의존성은 충족했다. 승인된 실제 DB 적용은 여전히 V10 → V11 순서다.
  새 JPA 매핑은 updated_at을 읽으므로 V11 스키마 적용 후 새 애플리케이션을 배포해야 한다.
  기존 코드에는 컬럼 추가·기본값·트리거가 호환되지만, 새 코드는 V11 없이 실행할 수 없다.
- Flyway는 번호 공백 자체를 막지 않는다. V9에서 V11을 먼저 적용하면 이후 V10이 기본
  outOfOrder=false에서 누락될 수 있다. PR·릴리스 검토에서 선후관계를 확인해야 한다.
- 이전의 임시 V10 테스트 경로와 API21_V10_FIXTURE_DIR 지원을 제거했다.
  이번 검증은 classpath의 정식 V1~V11과 병합된 전체 애플리케이션 코드를 사용한다.
- 기존 V1~V10은 수정하지 않는다. CI init은 sort -V로 SQL을 적용하므로 V11도 자동 발견한다.
  V11은 테이블 수를 늘리지 않는다. PR #93의 CI 테이블 수 수정과 충돌하는 변경도 추가하지 않았다.
- V11은 기존 행 UPDATE·NOT NULL 검증·DDL을 한 트랜잭션에서 수행한다. ALTER TABLE의 배타 잠금과
  백필 비용이 있어 실제 적용 전 행 수·실행 시간·잠금 대기 제한을 검토해야 한다.
  로컬에서 잠금 시간 초과 시 DDL·백필·트리거·이력의 롤백과 재시도를 검증한다.

## 동시성과 남은 계약 차이

READ COMMITTED에서 사용자 PESSIMISTIC_WRITE → 본인 나무 PESSIMISTIC_WRITE 순서다.
사용자 잠금 후 회원 상태와 소유권을 재검증한다. API-19도 같은 사용자 잠금을 먼저 사용한다.
ENDED→ACTIVE에서 ACTIVE+PAUSED를 다시 세고 5그루면 TREE409_2다. PAUSED는 점유를 유지하고
ENDED만 반환한다. 동일 상태 요청은 새 슬롯을 요구하지 않는다.
잠금 조회는 대기 중 바뀐 대상 행을 재평가하고, 이후 COUNT는 새 문장 스냅샷으로 직전 작성자의
커밋을 읽는다. 트랜잭션 전체 고정 스냅샷은 아니다. Hobby 쓰기 잠금은 추가하지 않는다.
API-18·20의 REPEATABLE READ 조회와 API-19 생성 응답 계약은 유지한다.

기존 공통 오류 메시지·필드 검증 result 객체 및 TREE409_2 메시지는 노션 예시와 차이가 남는다.
코드는 COMMON400_1, AUTH401_2, AUTH403_1\~3, TREE404_1, TREE409_2\~3을 유지한다.
API-18\~20·전역 인증·예외 정책은 이번에 바꾸지 않았으며, 공통 오류 계약 정비 대상으로 남긴다.
COMMON429_1은 별도 공통 Rate Limit 범위다. V1의 PAUSED 슬롯 반환 주석도 현재 정책과 다르지만
기존 Flyway는 수정하지 않는다.

이번 API-21은 API-18\~20과 같은 저장 단계 0\~5를 반환한다. 외부 1\~6 일괄 전환은
[공통 계약 변경 계획](api-stage-number-contract-plan.md)으로 분리했다. API-21만 +1 하지 않는다.

## 검증

기존 HTTP 통합 46개 사례를 유지하고 시간 계약 기대값만 정식 저장 계약에 맞췄다.
실제 PostgreSQL 별도 연결의 잠금 대기·동시 재시작·심기·상충 전환·대기 중 회원 상태 변경을 검증한다.
DB·JPA 재조회 일치, 동일 상태 xmin·updatedAt 불변, 생성 기본값, 잠금 대기 후 쓰기 시각도 검증한다.
Flyway는 기존 행 백필·V1~V11 순서·제한 계정 트리거·롤백을 포함한다.

2026-11-07 develop 4bcf820 통합 후 새로 실행한 로컬 결과:

- PostgreSQL 17.11·Redis 7.4.12, 루프백 전용 임시 컨테이너·제한된 app 계정 사용.
- 병합된 전체 코드와 정식 V1~V11의 전체 단위·통합·Flyway: 426개 통과.
- V3 제외 CI 스키마의 전체 도메인·공통 예외: 414개 통과.
- API-21 통합 47개, Flyway 12개(기존 9개 + V11 3개). 실패·오류·스킵 0건.
- API-18 통합 22개·단위 3개, API-19 통합 28개·단위 8개, API-20 통합 28개 회귀 통과.
- API-08·09 및 API-06 프로필 이미지 연동 회귀 16개 통과. 실제 S3 호출은 mock 처리했다.
- PR #96을 포함한 Hobby 통합 12개 및 User 통합 9개도 통과했다.
- Spotless, Java·테스트 컴파일, git diff --check 통과.
- 두 실행 모두 cleanTest 후 새 DB·Redis로 수행했다. 이전 보고서는 집계에 사용하지 않았다.
  새 SDK 캐시 부족으로 테스트 전에 중단된 시도는 의존성을 준비한 뒤 전체 재실행했다.
- GitHub CI·CodeRabbit 결과는 PR 검사·리뷰에 별도로 기록한다. 실제 AWS RDS 적용은 미실행이다.
