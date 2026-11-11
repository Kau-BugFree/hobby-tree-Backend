# API-34 FastAPI 연동

Issue #155. AI 저장소 `develop`의 `9364bface2638fb62c54edef6782ea58d5d73df9`
`activity-coach/backend/app/backend_bridge.py` 계약을 기준으로 한다.
AWS 설치·배포·운영 데이터 적재·외부 생성 모델 호출은 이 변경에 포함하지 않는다.

## 앱 계약

팀 `API.md`의 API-34는 `history` / `replyId`를 쓰는 이전 초안이다.
2026-11-11 사용자 확인에 따라 이미 develop에 구현된 서버 대화 저장 방식
(`conversationId` / `messageId`)을 유지한다. 클라이언트 `history`로 세션을 복원하지 않는다.

- `POST /api/v1/coach/chat`: 서비스 JWT와 `Idempotency-Key` 필수.
  `{ "conversationId": null, "message": "요리 활동 추천해줘", "userHobbyId": null }`.
- 첫 요청은 conversationId를 생략한다. 성공 후 반환된 ID를 다음 질문에 전달한다.
  userHobbyId는 첫 요청의 본인 소유 나무만 허용하며 이후 요청에서는 기존 맥락을 유지한다.
- 현재 AI는 `cooking`만 지원한다. 나무 맥락이 없으면 요리 코치를 사용한다.
  다른 취미나 지원 중지된 취미의 나무는 `COACH400_2`로 거절한다.
- 성공 응답의 기존 필드를 유지한다. `references[].sourceCode`를 추가한다.
  ACTIVITY의 sourceId는 공개된 요리 활동의 숫자 DB ID다.
  KNOWLEDGE/RECIPE의 sourceId는 null이며 sourceCode에 AI의 원래 문자열 식별자를 보존한다.
  프론트는 null을 0이나 링크 대상으로 변환하지 않아야 한다.
- suggestedActivities의 activityId·title은 같은 취미의 PUBLISHED DB 활동에서 조회한다.
  없는 코드·비공개 코드·다른 취미 코드는 전체 응답을 502로 처리한다.
- `GET /api/v1/coach/conversations`, `GET /api/v1/coach/conversations/{id}/messages`에서
  저장한 실제 답변·근거·활동을 조회한다. 성공 메시지가 없는 예약 대화는 목록에서 숨긴다.
- 신규 질문은 새 키, 실패한 질문의 재시도는 **같은 키와 같은 본문**을 사용한다.
  앱은 재시도 키와 본문을 보존하고, 로그아웃 시 다른 회원의 대화 ID·화면 상태를 비운다.

## 설정

기본값은 비활성화이며 임시 성공 답변 대신 503을 반환한다.
Spring 설정은 다음과 같다. 서버 전용 토큰은 프론트·저장소·로그에 넣지 않는다.

| Spring property | 환경변수 | 기본값 |
| --- | --- | --- |
| coach.ai.enabled | COACH_AI_ENABLED | false |
| coach.ai.base-url | COACH_AI_BASEURL | 없음 |
| coach.ai.service-token | COACH_AI_SERVICETOKEN | 빈 값 |
| coach.ai.connect-timeout | COACH_AI_CONNECTTIMEOUT | 2s |
| coach.ai.request-timeout | COACH_AI_REQUESTTIMEOUT | 45s |

base-url은 경로 없는 고정 HTTP(S) origin이다. 예: 격리 Docker의 `http://coach:8003`.
전체 응답 수신 제한은 최대 120초로 제한한다. 응답 본문은 수신 중 256 KiB로 제한한다.
자동 리다이렉트·자동 재시도는 하지 않는다. 사용자 JWT는 전달하지 않고
`X-Coach-Service-Token`만 보낸다. FastAPI의 `COACH_BACKEND_TOKEN`과 같은 값을 사용한다.
운영 주입 방법은 별도 배포 변경에서 검토한다.

AI 요청은 `{conversationId, message, requestId, hobbyCode}`이며
requestId는 예약된 멱등 행 ID에서 생성한다. AI 응답의 conversationId,
answer/answerVersion, 근거·활동 목록 및 필드 길이를 검증한다.
answerVersion은 메시지에 저장한다. AI의 세션 ID와 내부 진단은 앱에 노출하지 않는다.

| 상황 | 앱 HTTP / code |
| --- | --- |
| AI 비활성화, 연결 실패, AI 429/503 | 503 / COACH503_1 |
| 전체 응답 타임아웃 | 504 / COACH504_1 |
| AI 인증·계약 오류, 5xx, 잘못된 응답·활동 코드 | 502 / COACH502_1 |
| 같은 대화의 이전 질문 결과 미확정 | 409 / COACH409_1 |

공통 멱등 키 충돌·처리 중 오류 코드는 기존 규칙을 유지한다.
오류 응답에 AI 원문·요청 헤더·서비스 토큰을 포함하지 않는다.

## 저장과 재시도

1. 활성 회원·입력 검증 → 기존 공통 멱등 claim.
2. 짧은 준비 트랜잭션에서 회원·대화 소유권 확인, 대화 예약,
   PENDING `response_body`에 `{conversationId, requestId, hobbyCode}` 보관.
3. 트랜잭션 밖에서 FastAPI 호출. 네트워크 대기 중 DB 잠금을 보유하지 않는다.
4. 짧은 완료 트랜잭션에서 회원 상태·대화·claim 소유권 재검증.
   USER/ASSISTANT 두 메시지와 근거·활동·최종 멱등 응답을 함께 커밋한다.

공통 claim/reclaim/complete/release의 기존 호출 계약은 유지한다.
코치만 예약 보존형 실패 처리를 사용한다. 예약 전 실패는 claim 삭제,
예약 후 실패는 예약을 보존하고 lease를 만료시켜 같은 요청을 즉시 재시도할 수 있다.
프로세스가 중단되면 기존 5분 lease 이후 같은 키로 복구한다.
오래된 실행자의 CAS 실패나 DB 커밋 실패 시 메시지를 모두 롤백한다.
OSIV가 켜져 있어도 회원 상태와 예약은 DB에서 다시 읽는다.

같은 대화의 다른 질문은 미확정 턴을 앞지를 수 없다. 타임아웃이나 응답 매핑 오류가
남아 있으면 원래 키로 복구해야 하며, 필요하면 새 대화를 시작할 수 있다.
AI가 이미 저장한 응답의 활동이 DB에 없으면 카탈로그를 정렬한 뒤 같은 키로 재시도한다.
PENDING 예약을 임의 삭제하면 AI 대화와 DB 대화가 어긋날 수 있으므로 금지한다.

새 테이블·Flyway 변경은 없다. AI SQLite는 영속 보관하고 **백엔드 환경별로 분리**해야 한다.
AI는 `backend-conversation:{conversationId}`로 세션을 찾으므로, 서로 다른 DB의 같은 숫자
ID를 같은 AI 상태 파일에 연결하면 안 된다. DB 복원 시 AI 상태도 함께 검토해야 한다.
코치의 '완료했어'는 Spring 활동 완료·나무 성장 API를 호출하지 않는다.

## 검증

- `CoachAiClientTests`: 실제 루프백 HTTP 서버로 헤더·본문, 오류 변환,
  malformed/과대 응답, 대화 ID 불일치, 본문 수신 타임아웃, 비활성화를 검사한다.
- `CoachIntegrationTests` + 기존 `IdempotencyKeyServiceIntegrationTests`:
  격리 PostgreSQL 17·Redis, 테스트 인증으로 36개 통과.
  첫 타임아웃 재시도, 커밋 실패, 오래된 claim 소유자, 동시 턴, 비활성 회원,
  공개 활동 매핑·null 근거 ID 이력까지 포함한다.
- `CoachLocalAiTests`: `AUTH_INTEGRATION_TEST=true`와 `COACH_LOCAL_AI_TEST=true`를
  명시적으로 설정할 때만 실행한다. 정상 CI는 외부 AI를 호출하지 않는다.
  격리 DB에 임시 386활동 카탈로그를 적재하고 실제 로컬 FastAPI와 1개 시나리오 통과:
  첫 질문 → 후속 질문 → 동일 키 재요청 → 이력 → 다른 회원의 접근 차단·별도 대화.
  테스트 인증 제공자만 mock이고 CoachAiClient·FastAPI는 실제 구현이다.
  외부 모델 키 없이 내부 Docker 네트워크에서 실행했으며 OSIV=false에서도 검증했다.
- 기존 적용 마이그레이션과 `ddl-auto=validate`는 유지한다.
  로컬 통합 DB에는 V1~V23 SQL을 적용했다. CI의 별도 Flyway 업그레이드 검사도 확인한다.

실제 앱 화면·카카오 로그인·운영 API 연결은 후속 리허설 범위다.
로그인은 팀원 작업이며 추천 #114, 설문 14항목 저장, FE 화면 연결은 이 PR에 포함하지 않는다.
