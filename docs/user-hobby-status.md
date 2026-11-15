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

V13은 user_hobbies.updated_at TIMESTAMPTZ NOT NULL을 추가한다.
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
V13 이후 실제 수정부터는 DB가 기록한 마지막 수정 시각을 제공한다.

## V10→V11→V12→V13 적용 순서

- 검증 기준 develop: 07247c595f3e0d4fb16436a77d5de32af9e956df (2026-11-07 확인).
  요청 당시 확인된 33a3ae9 이후 PR #105도 병합돼 있었다.
- develop의 V10은 프로필 이미지(PR #93), V11은 온보딩 취향 스키마(PR #101),
  V12는 온보딩 완료·약관 이력(PR #105)이다. 기존 V1–V12 SQL·체크섬은 수정하지 않는다.
- API-21의 기존 V11 후보를 처음 요청된 V12로 옮겨도 다시 충돌하므로, 사용자 확인 후
  V13__add_user_hobby_updated_at.sql로 재배정했다. 기존 API-21 V11 파일은 남기지 않는다.
  주석 외 ALTER/UPDATE/FUNCTION/TRIGGER 실행 본문은 기존 후보와 동일하다.
- 실제 순서는 V10 → V11 → V12 → V13이다. 기존 DB는 이미 적용된 이력 다음 버전부터 정순 실행한다.
  repair 또는 outOfOrder 설정으로 우회하지 않는다. 테스트도 새 DB에서 정순으로 실행한다.
- 새 JPA 매핑은 updated_at을 읽으므로 V13 적용 후 새 애플리케이션을 배포해야 한다.
  기존 코드에는 컬럼 추가·기본값·트리거가 호환되지만 새 코드는 V13 없이 실행할 수 없다.
- CI init은 기존 sort -V로 SQL을 적용하므로 V13도 자동 발견한다. V13은 테이블 수를 늘리지 않는다.
  최신 develop의 V12까지 포함한 CI 스키마는 public 테이블 24개다.
- V13은 백필·NOT NULL 검증·DDL을 한 트랜잭션에서 수행한다. 실제 적용 전 행 수·실행 시간·배타 잠금
  대기 제한을 검토해야 한다. 로컬에서는 잠금 시간 초과 롤백과 재시도를 검증했다.
- 기존 V10 데이터에서 V11의 notification_frequency·updated_at 보존과 취향 기본값을 확인하고,
  취향 값을 저장한 뒤 V12의 온보딩 완료 시각·약관 이력을 추가했다. V13 전후의 회원·취향·약관·
  활동 기록을 비교하고 나무의 새 updated_at 이외 값이 그대로인지 확인한다.

## CI 결과 검증

고정된 테스트 수 12 비교를 제거하고 scripts/ci/verify-migration-test-results.py를 사용한다.
cleanTest 후 생성된 단일 JUnit 보고서와 Java 소스의 모든 @Test 시나리오가 일치해야 한다.
기존 핵심 9개와 나무 수정 시각 3개는 필수 시나리오 집합으로 보호하므로 소스에서 삭제해도 실패한다.
새 테스트 추가는 자동 발견하며, 보고서 누락·중복·빈 실행·다른 클래스·카운터 불일치·실패·오류·스킵을
모두 차단한다. 미지원 테스트 선언·표시 형식도 검증을 우회하지 않고 명시적으로 실패한다.
JUnit 주입 매개변수 표시(method(Path))는 정상 @Test로 인식한다. Python -O에서도 검증이 유지된다.

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

API-18~21은 함께 외부 currentStageNumber를1~6으로 반환한다. 저장0~5에서 응답 경계에만
공통 변환기를 적용하며 상태·시간·슬롯 정책은 유지한다.
세부 경계는 [공통 단계 계약](api-stage-number-contract-plan.md)을 따른다.

## 검증

기존 HTTP 통합 46개 사례를 유지하고 시간 계약 기대값만 정식 저장 계약에 맞췄다.
실제 PostgreSQL 별도 연결의 잠금 대기·동시 재시작·심기·상충 전환·대기 중 회원 상태 변경을 검증한다.
DB·JPA 재조회 일치, 동일 상태 xmin·updatedAt 불변, 생성 기본값, 잠금 대기 후 쓰기 시각도 검증한다.
Flyway는 기존 행 백필·V1–V13 순서·제한 계정 트리거·롤백을 포함한다.

2026-11-07 develop 07247c5 통합 후 새로 실행한 로컬 결과:

- PostgreSQL 17.11·Redis 7.4.12, 루프백 전용 새 임시 컨테이너·제한된 app 계정 사용.
- V1–V13 전체 신규 적용·업그레이드·JPA validate 및 전체 Java 단위·통합: 452개 통과.
- V3 제외 CI 스키마의 도메인·공통 예외: 440개 통과.
- API-21 HTTP 통합 47개(실제 PG 경쟁·잠금 대기 11사례 포함), Flyway 12개. 실패·오류·스킵 0건.
- API-18 통합 22개·단위 3개, API-19 통합 28개·단위 8개, API-20 통합 28개 회귀 통과.
- 프로필 이미지 16개, 온보딩 12개, 취향 14개, 회원 9개, 알림 22개, Hobby 12개도 통과.
- CI 증거 검증기 회귀 14개는 일반 Python과 -O 모드 모두 통과했고 실제 Flyway XML도 검증했다.
- Spotless, Java·테스트 컴파일, git diff --check 통과.
- 두 Java 실행은 cleanTest 후 새 DB·Redis로 수행했으며 이전 실행 결과는 집계하지 않았다.
- GitHub CI·CodeRabbit 결과는 PR #97 검사·리뷰에 기록한다. 운영 AWS·실제 RDS 변경은 미실행이다.
