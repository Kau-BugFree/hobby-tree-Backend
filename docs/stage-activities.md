# API-24 단계별 활동 목록 조회

> API-25 연동 변경: V17의 activities.thumbnail_url 도입 후 목록·상세 모두
> 활동 이미지 → 취미 이미지 → null 순으로 사용한다. 아래의 취미 이미지 전용 설명과
> V14 검증 수치는 API-24 최초 구현 당시의 기록이다. 현재 정책/검증은
> [activity-detail.md](activity-detail.md)를 함께 확인한다. ID 정렬·커서는 유지한다.

`GET /api/v1/hobby-stages/{stageId}/activities`를 구현한다. 계약 기준은
`HobbyTree_API_본문_49개.md`의 API-24와 이번 작업에서 지정한 조회·임시 정책이다.
HTTP 200 / `ACTIVITY200_2` / `단계별 활동 목록을 조회했습니다.`와 기존 ApiResponse를 사용한다.

## 기준과 범위

전달된 develop `fb5728330fb2ce2cea9991562ef0d4e4fb89e9e8` 이후 PR #108이 병합되어,
실제 시작 기준은 `4b2edd980c2a137193438127682e62ba9b9e623d`다.
이 기준에 V14__add_idempotency_keys.sql이 이미 있으므로 격리 테스트는 V1~V14를 사용한다.
기존 V1~V14 수정 및 신규 Flyway 추가는 하지 않는다. 추천·저장·기존 API 기능도 변경하지 않는다.
API-25~30, 실제 활동 시작·진행·완료·성장 갱신은 구현하지 않는다.

## 공개 범위와 인증

- 기존 JWT·회원 상태 처리를 사용하고 온보딩 완료를 확인한다. 누락·위조·만료·폐기 JWT는
  AUTH401_2, 온보딩 미완료는 AUTH403_2, 정지·탈퇴는 기존 AUTH403_1 / AUTH403_3이다.
- 실제 단계와 상위 `hobbies.is_supported=true`를 확인한다. 없는 단계와 비공개 취미의
  단계는 동일한 STAGE404_1 / `단계가 없거나 열람할 수 없음`이다.
- activities.review_status=PUBLISHED만 노출하고 DRAFT / REVIEW / REJECTED는 제외한다.
- 나무 소유·현재 단계·슬롯을 요구하지 않는다. 잠긴 단계도 미리 볼 수 있으며 활동 시작
  권한을 부여하는 것은 아니다. 후속 API-26이 ACTIVE 나무·소유권·진행 가능 단계를 검증해야 한다.
- 잘못된 ID·size·cursor는 COMMON400_1, 예기치 못한 오류는 기존 COMMON500_1 처리다.
  COMMON429_1은 별도 공통 Rate Limit 범위로서 이번 API에서 새 전역 제한을 추가하지 않는다.
- 응답에는 Cache-Control: no-store를 적용한다.

## 전체 응답 필드

| 필드 | 출처·정책 |
| --- | --- |
| result.stageId | 실제 hobby_stages.hobby_stage_id |
| result.hobbyId | 해당 단계의 hobby_id |
| result.stageNumber | ApiStageNumber.fromStored(): 저장 0~5 → 응답 1~6 |
| result.items | 해당 단계의 PUBLISHED 활동, 아래 임시 순서의 제한된 페이지 |
| result.nextCursor | 뒤 페이지가 있을 때만 서버 발급 서명 커서, 마지막이면 null |
| result.hasNext | size+1 조회에 추가 행이 있는지 |
| items[].activityId | activities.activity_id |
| items[].title | activities.activity_name |
| items[].thumbnailUrl | activities.thumbnail_url 우선, 없으면 hobbies.thumbnail_url, 모두 없으면 null |
| items[].durationMinutes | activities.expected_total_minutes |
| items[].estimatedCost | activities.estimated_cost_min |
| items[].difficulty | activities.difficulty 원본 |
| items[].indoorOutdoor | hobbies.indoor_outdoor |
| items[].isSaved | 요청 user_id와 activity_id에 해당하는 saved_activities 존재 여부 |

API-16·17도 입문 활동의 expected_total_minutes와 estimated_cost_min을 요약 시간·비용으로
사용하므로 이 매핑을 유지한다. 실제 0은 0으로 반환하되 미입력은 0으로 바꾸지 않는다.
활동의 시간·비용·난이도, 취미 썸네일이 DB NULL이면 필드를 유지하고 JSON null로 반환한다.
원문의 응답 예시는 nullable 허용 여부를 별도로 정의하지 않으므로 이 데이터 부재 표현은
기존 직렬화 정책을 따른다. FE의 null 처리와 최종 콘텐츠 필수값 계약 확인이 필요하다.
단계에 공개 활동이 없으면 items=[], nextCursor=null, hasNext=false다.

## 표시 순서: 노션 계약의 임시 미충족

원문은 **설정된 활동 노출 순서 → activityId**를 요구한다. V1~V14·Activity 엔티티·활동
Repository에 대응하는 표시 순서 필드가 없다. V6의 activity_sponsored_contents.sort_order는
활동 안의 광고 순서이며 활동 목록 순서로 재사용할 수 없다.

이번에는 DB 변경 없이 **activityId ASC**를 임시 정책으로 사용한다. 표시 순서를
하드코딩·추정하거나 컬럼을 임의로 추가하지 않는다. 이 구현은 노션 정렬 계약의 완전한
충족이 아니므로 테스트가 통과하더라도 최종 판정은 CONDITIONAL GO다.

## 커서와 DB 조회

- size 기본 20, 범위 1~50. 전체 활동을 메모리에 읽지 않고 SQL에서 activityId > afterId,
  activityId ASC 및 size+1 제한을 적용한다. Pageable은 항상 page=0으로 제한 용도만 사용한다.
- 마지막 반환 행 ID로 다음 커서를 생성한다. 추가 한 행은 hasNext 확인에만 사용한다.
- 커서에는 정렬 버전·userId·stageId·마지막 ID를 담고 Base64url 및 HMAC-SHA256으로 무결성을
  보호한다. 클라이언트는 내용을 해석·수정하지 않아야 한다. 암호화나 비밀 데이터 저장용은 아니다.
- 기존 serviceSigningKey에서 API-24 전용 문맥으로 파생한 키를 사용한다. JWT 키·서명 형식이나
  공통 인증 설정을 변경하지 않는다. 서명은 JWT 서명과 분리되며 인증 토큰으로 사용할 수 없다.
- 변조·손상·과도한 길이·다른 단계·다른 사용자·지원하지 않는 정렬 버전의 커서는 COMMON400_1이다.
  서명 키 교체 시 기존 커서는 유효하지 않게 되므로 첫 페이지부터 다시 조회해야 한다.
- size는 커서에 고정하지 않아 다음 요청에서 허용 범위 안의 크기로 변경할 수 있다.
- 삭제된 커서 기준 행을 다시 요구하지 않는다. 동일 정렬의 남은 ID로 계속 이동할 수 있다.
  각 요청은 별도의 스냅샷이므로 요청 사이의 신규 게시·삭제·비공개 전환까지 고정하는 것은 아니다.
  이미 통과한 ID가 나중에 공개되면 첫 페이지부터 다시 조회해야 보일 수 있다.

읽기 전용 REPEATABLE READ 트랜잭션에서 사용자·공개 단계/취미·페이지/저장 상태를 조회한다.
페이지는 활동과 요청 사용자 조건을 ON 절에 둔 SavedActivity LEFT JOIN 스칼라 쿼리 한 번이다.
기존 `(user_id,activity_id)` 복합 PK로 중복 행이 생기지 않고, 다른 사용자의 저장 여부도 섞이지
않는다. 단계/취미는 JOIN FETCH 한 번으로 읽으며 활동별 지연 로딩·저장별 추가 조회는 없다.
기존 API-31·32 Controller·Service·저장/취소 Repository 메서드는 변경하지 않는다.

## 커서 시크릿과 배포 구성

추가 환경 변수·신규 시크릿·인증 설정 변경은 없다. 기존 AuthConfig.serviceSigningKey가
auth.jwt-secret → JWT_SECRET을 Base64 디코딩하고 64바이트 이상인지 검증한 키를 주입한다.
일반 환경은 기존 JWT_SECRET 설정, ec2 프로필은 기존 configtree의
`/run/secrets/hobbytree/JWT_SECRET` 파일을 사용한다(APP_SECRETS_PATH로 경로 재정의 가능).
현재 배포의 시크릿 파일 주입·권한 관리 방식을 그대로 유지하며 비밀값을 코드·문서에 넣지 않는다.
소스의 문자열 `hobbytree/api-24/cursor-key/v1`은 파생 키의 공개 문맥 이름이며 비밀키가 아니다.
단위 테스트의 고정 바이트 배열도 격리된 테스트 입력일 뿐 배포 키로 사용하지 않는다.

동일 배포의 인스턴스는 기존 JWT 검증과 마찬가지로 같은 시크릿 설정을 사용해야 한다.
키는 시작 시 주입되며 기존 시크릿 교체·프로세스 재시작 절차를 따른다. 교체 후 이전 커서는
COMMON400_1로 거절되므로 첫 페이지부터 재조회한다. display_order 기반 정책은 새 버전과
새 payload/keyset을 함께 도입하고 ID 전용 v1을 의도적으로 거절하거나 별도 호환 경로로
처리해야 한다. 현재 구현은 api24-id-v1만 허용하므로 새 순서를 v1로 해석해서는 안 된다.

## API-25 이후 콘텐츠 스키마 계획

이번에 마이그레이션이나 데이터 변환을 선행하지 않으며 다음은 별도 승인할 설계 항목이다.

1. 단계 내 활동 표시 순서의 저장 위치·타입·NULL/동순위 처리·기존 데이터 백필·편집 정책을
   확정한다. 이어 `(display_order, activity_id)` 순서와 같은 조합의 keyset 조건·인덱스를 설계한다.
   새 정렬을 적용할 때 커서 버전을 바꾸고 ID 전용 커서의 전환/폐기 정책을 함께 정한다.
2. 활동 전용 이미지의 URL 또는 이미지 key·관리 방식·대체 취미 이미지 우선순위를 확정한다.
   현재 thumbnailUrl은 활동 전용 이미지가 아니라 취미 이미지라는 사실을 FE에 공유해야 한다.
3. API-25 상세와 목록이 시간·최소 비용·이미지·nullable 표현을 공유하도록 계약을 정리한다.
   스키마 제약을 확인하기 전 필수 숫자·이미지를 가짜 기본값으로 채우지 않는다.

## 검증

StageActivityIntegrationTests는 실제 HTTP/JWT/PostgreSQL·Redis를 사용해 모든 필드와 null,
단계 0~5/1~6, 공개 필터·빈 목록, 나무 없음·잠긴 단계, 저장 사용자 분리·불변성·API-31·32
연동, size 경계·안정적인 반복/다중 페이지·삭제 기준 행·새 게시·변조/범위 커서, 오류 및
회원 상태를 검증한다. Hibernate SQL 통계로 1개/50개 페이지에서 조회 수가 동일함을 확인한다.
StageActivityCursorTests는 서명 키·사용자·단계·변조·비표준 인코딩·길이 검증을 독립 검증한다.
실행 수치와 기존 API 회귀 결과는 완료 보고에 기록한다.

## 최종 로컬 검증 결과 (2026-11-07)

PostgreSQL 17.11 / Redis 7.4.12를 매 실행 별도 임시 컨테이너와 임시 자격증명으로 사용했다.
앱은 제한된 DML·시퀀스 권한 계정과 JPA validate / open-in-view=false로 실행했다.

| 실행 경로 | 테스트 | 실패 / 오류 / 스킵 |
| --- | ---: | --- |
| V1~V14 전체 스키마, 전체 Java·Flyway | 590 | 0 / 0 / 0 |
| V3 제외 CI 호환 스키마, 도메인·공통 오류 회귀 | 578 | 0 / 0 / 0 |

각 실행에는 API-24 HTTP 통합 45개 및 커서 단위 9개가 포함된다.
기존 API-16·17 HTTP 회귀 12개, API-18~22 HTTP 회귀 156개, API-23 HTTP 회귀 22개,
API-31·32 HTTP 회귀 10개도 모두 통과했다. 최신 develop의 추천 14개와 멱등성 7개도 통과했다.
전체 실행에는 기존 Flyway 12개와 실제 XML 결과 검증 게이트가 포함된다.
현재 전체 매핑 대상으로 Flyway latest(V14) 신규 적용과 JPA validate도 검증했다.
Spotless·Java/테스트 컴파일·git diff --check 모두 통과했다.

기존 마이그레이션·API-31·32 및 기존 나무 서비스·컨트롤러는 변경하지 않았다.
위 수치는 로컬 검증 결과이며 최종 커밋에서 재실행하고 원격 CI·CodeRabbit 결과는 PR에
별도로 기록한다. 실제 AWS/RDS 변경은 수행하지 않았다. 노션의 표시 순서 계약을 완전히
충족하지 못하므로 승인된 범위는 Issue·Draft PR 생성과 검증까지다. 병합 승인은 보류하며,
최종 판정은 **CONDITIONAL GO**로 유지한다.
