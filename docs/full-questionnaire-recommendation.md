# 14항목 온보딩과 추천 연결 계약

## 범위와 기준

API-10~15의 추가 계약이다. 팀 API.md의 기존 5필드 저장·조회·추천 형식은 유지한다.
사용자가 선택한 AI 14항목 전체 연결을 위해 `questionnaire`와 `engineResult`를 추가했다.
AI 기준은 `Kau-BugFree/hobby-tree-AI` 커밋 `9364bface2638fb62c54edef6782ea58d5d73df9`의
`src/hobby-recommender`다. `onboarding-ai`의 questionnaire/catalog/rules JSON은 이 커밋의 원본이다.
이 엔진은 FastAPI에서 실행하는 `rules-2.0.0` 규칙 엔진이며 외부 LLM을 호출하지 않는다.
기존 Issue #27의 선택지와 #114의 RAG/Gemini 추천 사유 작업을 대체하거나 자동 종료하지 않는다.

## API-10 선택지

`GET /api/v1/onboarding/options`는 기존 루트 `optionsVersion=2026-11-07`과 선택지를 유지한다.
새 화면은 `result.questionnaire.version=onboarding-2026-10-13`, `groups`, `resources`를 사용한다.
Q01~Q13 중 Q09가 `q09Place`, `q09Exclude`로 나뉘므로 답변 그룹은 14개다.
별도 자유 입력 Q14는 `context_text`다. 정확한 선택지 문자열, 다중 선택 최대 개수와 배타 조건은
API-10의 정의를 그대로 사용한다. 루트 버전과 새 설문 버전을 혼용하지 않는다.

## API-11 저장 / API-12 조회

`PUT /api/v1/users/me/preferences` 예시:

```json
{
  "optionsVersion": "onboarding-2026-10-13",
  "expectedVersion": 0,
  "questionnaire": {
    "schema_version": "onboarding-2026-10-13",
    "answers": {"q02": ["독서"], "q06": "30분", "q09Exclude": ["없음"]},
    "custom_values": {},
    "resources": {"owned_book": true, "library_access": false},
    "monthly_committed_krw": 10000
  }
}
```

위 자원 키는 API-10에서 확인하고 사용한다. 알려지지 않은 자원 키는 거절한다.
`answers`/`custom_values`/`resources` 생략은 `{}`로 정규화한다. 명시적 null 컨테이너는 거절한다.
개별 답변 null/누락/빈 다중 선택은 미응답이다. 미확인 자원은 키를 생략하며 false와 다르다.
Q08 물건 응답으로 자원 true를 추정하지 않는다. 자원 값은 boolean만 받는다.
`context_text`는 최대 200 유니코드 코드 포인트이며 공백만 있으면 제거한다.
직접 입력 선택과 대응 `custom_values`는 함께 보내야 한다.

| 직접 입력 | 필드 | 제한 |
|---|---|---|
| Q04/Q05 직접 금액 입력 | q04_amount_krw / q05_amount_krw | 정수 0~1,000,000,000 |
| Q06 직접 시간 입력 | q06_minutes | 정수 1~1,440 |
| Q02 다른 취미 직접 입력 | q02_other | 비공백 1~200자 |
| Q09 제외 조건 직접 입력 | q09_exclude_text | 비공백 1~500자 |

`monthly_committed_krw`는 정수 0~1,000,000,000 또는 null/생략이다. 숫자 문자열·실수·boolean은 거절한다.
알 수 없는 답변/최상위 필드, 중복 선택, 배타 선택 위반은 PREFERENCE400_1이다.
버전 불일치는 기존 PREFERENCE409_1, expectedVersion 충돌은 기존 취향 버전 충돌 오류다.
새 설문과 기존 budgetLevel/availableTimeMinutes/indoorOutdoor/difficultyPreference/preferredCategoryIds를
한 요청에 섞지 않는다. 새 저장 시 기존 4개 스칼라는 null, 카테고리는 []가 되며 알림 설정은 보존한다.
기존 5필드 저장을 다시 사용하면 questionnaire는 null이 된다. 변경이 있을 때만 preferencesVersion을 증가시킨다.
키 순서만 다른 JSON 재저장은 버전을 증가시키지 않는다.

저장·조회 응답은 기존 필드와 새 `questionnaire`를 반환한다. 기존 프로필은 이 새 필드를 생략한다.
API-13 완료는 별도로 호출한다. 필수 약관/닉네임과 preferencesVersion 검증을 유지하며 자동 동의하지 않는다.

## API-14 생성 / API-15 최신 조회

기존 POST `/api/v1/recommendations` 요청과 `Idempotency-Key` 헤더를 유지한다.
`preferencesVersion`은 저장 응답 값을 사용한다. `count`는 기존처럼 1~5, 생략 시 3이다.
Q11의 하나만 선택하는 화면은 count=1을 명시한다. 저장 설문에 top_k를 넣지 않는다.

새 설문 사용자는 FastAPI `/api/v1/recommendations`로 호출한다. AI 후보 3개 전체를 받아
앱 DB `hobby_code`로 검증한 뒤 보유·명시적 제외·미지원 취미를 제거하고 count로 제한한다.
보유는 기존 정책대로 ENDED를 포함한 모든 사용자 나무다. 문자열 부분 일치나 임의 ID 매핑은 하지 않는다.
AI 취미 코드가 DB에 없으면 502이며 결과 저장을 롤백한다. 배포 전 cooking/instrument/reading을 확인해야 한다.

기존 응답의 `items`는 실제 DB hobbyId를 담으며 새 `hobbyCode`, `score`를 추가한다.
원본 상태·버전·점수 근거·자원 확인 목록은 `engineResult`에 저장/반환한다.
앱에서는 score를 **추천 점수 n점**으로 표시한다. 성공 확률이나 적합 확률 %로 표시하지 않는다.

| engineResult.status | 앱 처리 |
|---|---|
| recommended | items의 실제 DB 취미 카드와 engineResult의 근거·확인 목록 표시 |
| browse | 사용자가 직접 취미 목록 탐색 |
| needs_review | 자유 입력/제외 조건 검토 필요 표시, 구조화된 답변 수정 후 재저장·재추천 |
| no_match | 추천 후보 없음 안내, 조건 수정 또는 직접 탐색 |

네 상태 모두 API-14의 생성 성공 201이며, 추천 후보가 없어도 API-15로 마지막 상태를 조회할 수 있다.
AI 활동의 `needs_confirmation`은 자원/조건 확인 UI를 표시해야 한다. 자원 확인 답변은 API-11로 저장하고
새 버전 및 새 Idempotency-Key로 추천한다. 자유 입력은 Spring이나 AI 규칙 엔진이 임의로 구조화하지 않는다.
카탈로그 고정 확인 문구는 자원 true만으로 사라지지 않을 수 있다.

AI 입문 활동 9개의 activity_id는 앱의 386개 활동 activity_id와 다르다.
이 PR은 `first_activity_id`를 null로 보존하고 AI 문자열 코드를 engineResult에만 둔다.
AI activity_id를 앱 활동 상세·완료 API에 전달하지 않는다. 앱 활동은 선택한 실제 hobbyId로 조회한다.
별도 정확한 코드 매핑/카탈로그 동기화 작업 없이 AI 입문 활동을 앱 활동으로 직접 선택하는 기능은 지원하지 않는다.

API-15는 새로운 AI 호출 없이 저장한 응답을 읽는다. 나중에 숨긴 취미 카드는 제외한다.
설문 버전이 바뀌면 기존 isStale=true를 반환한다. 추천 생성과 멱등 응답은 같은 트랜잭션에 저장한다.
AI 호출 동안 DB 트랜잭션을 유지하지 않고 완료 시 계정 상태·설문 버전/내용을 다시 확인한다.
동일 키 동시 요청은 기존 409, 동일 키·본문 재시도는 최초 결과, 다른 본문은 기존 409 충돌이다.

## 장애와 설정

| 상황 | HTTP / 코드 |
|---|---|
| 비활성화, 연결 실패, AI 429/503 | 503 / AI503_1 |
| 전체 응답 제한 시간 초과 | 504 / AI504_1 |
| 다른 비정상 상태, JSON·버전·활동 코드·제약 검증 실패 | 502 / AI502_1 |

정확한 오류 문자열은 RecommendationErrorCode를 기준으로 한다.
새 설문은 장애 시 기존 5필드 RULE_BASED_V1로 자동 변환하지 않는다. 14항목 자원·제외 조건을 잃기 때문이다.
실패한 새 결과는 저장하지 않으며 동일 키로 재시도할 수 있다. 이전 추천 조회와 직접 탐색은 별도 경로다.
기존 5필드 프로필의 RULE_BASED_V1은 그대로 유지한다.

기본 `recommendation.ai.enabled=false`. 활성화하려면 환경별로 다음을 지정한다:

```text
RECOMMENDATION_AI_ENABLED=true
RECOMMENDATION_AI_BASEURL=http://<private-recommender-host>:8000
RECOMMENDATION_AI_CONNECTTIMEOUT=2s
RECOMMENDATION_AI_REQUESTTIMEOUT=10s
```

baseUrl은 경로/쿼리/userinfo 없는 고정 HTTP(S) origin이다. 리다이렉트와 사용자 JWT 전달은 금지한다.
응답 최대 256 KiB, 전체 요청 시간 최대 설정 30초다. 운영 포트는 백엔드에서만 접근하도록 제한한다.

## 스키마·검증·배포 경계

V24는 nullable JSONB `user_preferences.questionnaire`, `recommendations.engine_result` 두 컬럼만 추가한다.
기존 행 업데이트나 데이터 적재는 하지 않는다. 적용된 V1~V23은 수정하지 않고 ddl-auto=validate를 유지한다.
애플리케이션 rollback 시 컬럼은 유지한다. 이전 앱은 새 설문을 이해하지 못하므로 새 설문 저장을 먼저 중단하고
구버전으로의 rollback 영향(추천 재생성 불가)을 평가해야 한다. 운영 마이그레이션·배포는 별도 승인 대상이다.

단위 검증: QuestionnaireContractTests, RecommendationAiClientTests.
격리 PostgreSQL/Redis: FullQuestionnaireIntegrationTests와 기존 Preference/Onboarding/Recommendation 통합 테스트.
실제 로컬 FastAPI: AUTH_INTEGRATION_TEST=true 및 RECOMMENDATION_LOCAL_AI_TEST=true인
RecommendationLocalAiTests. internal Docker 네트워크의 검증된 로컬 추천 컨테이너만 지정한다.
운영 DB, 운영 추천 POST, 유료 모델 호출로 이 테스트를 대체하지 않는다.
