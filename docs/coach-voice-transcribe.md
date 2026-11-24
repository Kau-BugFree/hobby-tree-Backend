# 하비 코치 음성 → 텍스트 (STT)

Issue #284. AI팀 명세 v1.0 및 [STT 커밋 e81cfbf](https://github.com/Kau-BugFree/hobby-tree-AI/commit/e81cfbf88a49e93aa8f702a9e8bf871f01edb6a9) 기준.
AI팀은 STT 단독 선반영을 확인했지만 다른 코치 담당자 변경과 병합은 미완료다.
이 PR은 **Spring API 구현**이다. 기존 GPU 이미지에는 STT가 없으며 AI 이미지 통합·Google 인증 설치·운영 배포는 별도다.

## 앱 요청

`POST /api/v1/coach/transcribe`

- 서비스 JWT 필수, ACTIVE 회원만 허용. 공통 필터를 통과하는 미온보딩 회원도 서비스에서 거절한다.
- `multipart/form-data`의 `file` 음성 파일. JSON/base64 요청이 아니다.
- 파일 최대 **7MiB = 7,340,032 bytes**, multipart 전체 최대 **8MiB**.
- 녹음 최대 60초. 앱이 정지시키며 AI/Google이 초과 음성을 거절한다. Spring은 길이를 측정하지 않는다.
- 파일 part의 MIME은 구체적인 `audio/*` 형식(예: `audio/mp4`, `audio/webm`, `audio/wav`).
- MIME 누락/빈 값/`application/octet-stream`인 경우 파일명이 .m4a이고 MP4 ftyp 표식이 있는 때만 audio/mp4로 보정한다.
  표식 확인은 디코딩 검증이 아니다. 실제 형식 판정은 AI/Google이 수행한다.
- conversationId, 사용자 ID, Idempotency-Key는 받지 않는다.
- FormData의 최상위 Content-Type/boundary를 직접 만들지 말고 앱 런타임에 맡긴다.

성공:

```json
{
  "isSuccess": true,
  "code": "COACH200_4",
  "message": "음성을 텍스트로 변환했습니다.",
  "result": { "text": "간장 두 큰술을 넣으면 되나요?" }
}
```

결과는 입력창에 표시하여 편집한 뒤 기존 `/api/v1/coach/chat`으로 보낸다.
변환만으로 대화·메시지·멱등 기록을 생성하거나 Haiku를 호출하지 않는다.
변환 텍스트를 2,000자로 자르지 않는다. 채팅 제한을 넘으면 앱에서 내용을 보존하고 편집 안내한다.

## 오류와 재시도

| HTTP / code | 의미 |
| --- | --- |
| 400 / COMMON400_1 | file 누락, 잘못된 multipart |
| 400 / COACH400_3 | 빈 음성, 읽기 실패, AI가 거절한 음성(길이 포함) |
| 413 / COACH413_1 | 파일/전체 업로드 한도 초과 |
| 415 / COMMON415_1 | 요청 전체가 multipart가 아님 |
| 415 / COACH415_1 | 음성 part의 MIME 오류 또는 AI 415 |
| 422 / COACH422_1 | 말소리를 인식하지 못함 |
| 429 / COMMON429_1 | 사용자 호출 횟수 또는 실행 슬롯 제한; Retry-After 참고 |
| 502 / COACH502_2 | 잘못된/과도한 AI 응답, 예상하지 못한 상태 |
| 503 / COACH503_2 | 기능 비활성, 연결 실패, AI 인증·과부하·Google 이용 불가 |
| 504 / COACH504_2 | STT 전체 AI 응답 시간 초과 또는 AI 504 |

기존 인증 오류는 공통 JWT/활성 회원 계약을 따른다.
AI 서비스 토큰의 401/403은 앱의 로그인 401로 전달하지 않는다.
AI 응답 원문·내부 진단·음성·인증 정보는 오류에 포함하거나 로그에 기록하지 않는다.
Google 설정 오류도 AI에서 400으로 분류될 수 있으므로 반복적인 400은 운영 설정도 점검한다.

자동 재시도/리다이렉트 없음. 재요청은 새 Google 호출이 될 수 있으며 멱등 재생을 보장하지 않는다.
앱은 중복 탭을 막고 실패 시 사용자가 명시적으로 재시도하도록 한다.
채팅의 동일 Idempotency-Key 재시도 규칙을 STT에 적용하지 않는다.

## 제한과 설정

- `coach.stt.enabled=false` 기본. 활성화에는 `coach.ai.enabled=true`와 유효한 기존 AI 설정이 필요하다.
- `coach.stt.request-timeout=45s`, 0초 초과/최대 60초. 헤더뿐 아니라 응답 본문 수신까지 포함한다.
- 기존 `coach.ai.base-url/service-token/connect-timeout`을 재사용한다.
- AI 경로는 `POST /backend/coach/transcribe`. multipart를 해제한 raw audio bytes와 part MIME,
  `X-Coach-Service-Token`만 전달한다. 사용자 JWT는 전달하지 않는다.
- AI Google HTTP 기본 30초 < Spring STT 45초 < 앱 업로드 포함 60~75초를 권장한다.
  업로드 수신·전처리 지연을 포함해 실제 기기로 검증한다. 기존 채팅 timeout은 변경하지 않는다.
- Redis `auth:ratelimit:coach-stt:{userId}`: 60초 고정 창당 3회. 채팅 budget과 독립이다.
- 메모리 보호: 백엔드 프로세스당 동시 변환 2개, 동일 회원 1개. 대기열 없이 429.
  여러 인스턴스를 쓰면 동시성 한도는 인스턴스 수만큼 늘어나므로 AI 전체 한도는 별도 검증한다.
- 크기/회원/활성화 검사를 통과한 시도는 MIME 오류·동시성 거절·AI 오류도 분당 예산을 소비할 수 있다.
  분당 제한은 Google의 일일 비용 상한을 보장하지 않는다. 실제 비용 상한은 활성화 승인 때 정한다.
- AI 응답은 성공/실패 모두 수신 중 256KiB로 제한한다.

Spring multipart 제한은 애플리케이션 공통 설정이다. 기존 프로필 이미지는 S3 presigned 업로드 방식이며 직접 multipart 전송을 사용하지 않는다.
threshold=0으로 업로드가 servlet 임시 파일을 사용할 수 있고 요청 정리 시 삭제된다.
서비스는 스트림을 닫고 오디오/텍스트를 DB·로그·별도 파일에 보존하지 않는다.
운영에서는 쓰기 가능한 tmpfs 임시 경로와 용량·동시 업로드 한도를 검증해야 한다.
서버 장애 시 정리와 외부 Google 전송 고지는 별도 운영/개인정보 검토 대상이다.
Flyway 변경 없음, ddl-auto=validate 유지.

## 검증과 활성화 전 조건

- 단위: 비활성 회원 전 상태, 크기·MIME·m4a fallback, 동일 사용자/동시 2개, 오류 후 슬롯 반환.
- 루프백 모의 HTTP: raw bytes·서버 토큰, 상태 매핑, 응답 크기/형식, 본문 지연 timeout, 무재시도·리다이렉트 차단.
- 격리 PostgreSQL/Redis + 실제 HTTP: JWT·미온보딩, multipart 파싱/크기, 호출 예산/Retry-After, 대화 저장 없음.
- 기존 코치와 공통 오류 처리 회귀 검사.
- 실제 기기 녹음 E2E: 프론트팀 담당. AI팀의 기존 시험은 합성 음성으로만 수행됐다.
- Google 키는 저장소·CI·이미지·프론트에 넣지 않는다. 기존 전달 파일은 이번 구현에서 읽지 않는다.
- GPU STT 이미지 통합, 기존 RAG/Haiku 고정·세션 회귀, Google 인증·결제/권한 확인,
  운영 설정 전달 가드 보완 및 이미지 교체는 별도 승인 전 준비가 필요하다.
- 현재 ai.properties 허용 키 계약을 임의로 늘리거나 운영 파일에 STT 값을 추가하지 않는다.
  배포 경로에서 STT 설정 전달을 명시적으로 지원한 뒤 활성화한다.
