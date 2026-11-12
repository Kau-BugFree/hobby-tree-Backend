# Haiku 5.5 연결 준비

Issue #181. 코드·격리 검증만 포함한다. 병합·PR CI가 운영 적용이나 유료 호출을 실행하지 않는다.
AI source는 기존 배포와 동일한 `9364bface2638fb62c54edef6782ea58d5d73df9`로 고정한다.
기존 이미지의 앱은 Haiku Messages API를 지원하므로 이번 변경은 이미지 교체 없이
호스트에 bind mount된 런처와 Compose override로 적용할 수 있다. 실제 적용 전 digest와
이미지 내부 소스 일치는 다시 확인한다. 백엔드·추천기·Flyway·카탈로그 변경은 없다.

## 설정

- 기본 `compose.yml`은 오프라인이다. 상속된 API 키가 있어도 사용하지 않는다.
- 별도 `compose.haiku.yml`을 함께 선택하면 `COACH_LLM_ENABLED=true`가 된다.
- `/opt/hobbytree-ai/secrets/anthropic_api_key`는 **키 값 하나만** 가진 파일이다.
  전달받은 env 전체나 `ANTHROPIC_API_KEY=...` 행을 그대로 넣지 않는다.
  소유자 10001:10001, 권한 0400, 부모 디렉터리는 root 관리 및 다른 사용자 쓰기 금지.
  읽기 전용 `/run/secrets/anthropic_api_key`로 mount한다. 실제 키를 Compose 환경변수,
  명령 인자, Git, 이미지, CI secret, 출력 또는 첨부 산출물에 넣지 않는다.
- 기존 `coach_service_token`을 보존한다. Anthropic 키와 서비스 토큰은 서로 다른 용도다.
- 런처는 모델을 `claude-haiku-5-5`, effort `medium`, 최대 출력 4096토큰,
  Claude 단일 요청 timeout 10초, SDK 자동 재시도 0으로 고정한다. 공급자 URL override는 제거한다.
  API 키 누락·형식·파일 권한 오류는 비밀을 출력하지 않고 기동 실패 처리한다.
- `.env`보다 프로세스 환경이 우선하므로 AI 소스의 기본 Opus 설정은 사용되지 않는다.
- 공식 계약: https://platform.claude.com/docs/en/models/haiku-5-5/migration-guide

## 검증 경계

기본 테스트는 파일 주입/옵트인/오류/실행 인자를 검사한다. SDK 계약 테스트는 위 고정 AI
소스와 anthropic 2.4.0의 실제 직렬화·응답 파싱을 사용하고 HTTP transport만 모의로 바꾼다.
키는 테스트 전용이고 소켓 연결은 차단한다. 모델 다운로드·실제 .env·운영 DB는 사용하지 않는다.
CI는 배포 소스의 Haiku 모델 선택, JSON 구조화 출력, 인증/쿼터/서버/timeout 오류,
오프라인 호출 0회를 확인한다. 키의 유효성·잔액·모델 권한·실제 답변 품질은 증명하지 않는다.

키를 설정해도 모든 질문이 Claude를 호출하는 것은 아니다. 검색 우선 경로는 직접 응답할 수 있다.
`/health`의 `llm_configured=true` 역시 **설정 존재**만 의미한다. 실제 생성 성공은 호출 결과와
모델 사용량으로 따로 확인해야 한다. 인증 실패를 직접 검색 응답으로 처리하는 기존 AI 동작도
유지되므로 HTTP 200만으로 합격 판정하지 않는다.

AI는 한 사용자 턴에서 여러 생성 요청을 수행할 수 있다. 4096토큰은 **호출당** 상한이며
10초도 **호출당** 제한이다. 전체 턴의 비용·시간 상한을 의미하지 않는다. Spring의 45초
제한과 프론트 대기 시간을 실제 승인된 시나리오로 측정해야 하며, 45초를 넘거나
생성 실패가 숨겨지면 공개 시연 승인을 중단한다. 이 PR에서 무조건 timeout을 늘리지 않는다.
SDK 재시도 0은 AI 코드의 구조화 출력 보정·후속 판단 호출을 끄는 설정이 아니다.

## 승인 후 적용 계획

1. AWS 재인증 후 읽기 전용으로 현재 AI image digest·런처·Compose·마운트·health를 확인한다.
   백엔드 V24 및 기존 연결을 재확인한다. 다른 작업의 변경과 불일치하면 멈춘다.
2. 기존 런처·Compose·systemd 설정을 root 전용 위치에 보존하고 파일 SHA를 기록한다.
   키는 지정 로컬 원본에서 보호된 전달 경로로 전송하고 값·해시를 로그에 남기지 않는다.
   서버에는 값만 저장하고 10001:10001/0400 및 파일 크기·형식만 검사한다.
3. 검토된 런처·override 및 `haiku.conf`를 설치한다. drop-in은
   `/etc/systemd/system/hobbytree-ai.service.d/haiku.conf`에 놓고 `daemon-reload`한다.
   `prepare-host.sh`는 최초 설치 전용이므로 재실행하지 않는다.
4. 기존 runtime.env와 digest를 유지하고 Compose 두 파일로 `config --quiet` 검증 후
   `up -d --no-deps --force-recreate --wait --wait-timeout 600 coach`만 실행한다.
   추천기와 Spring 컨테이너는 재생성하지 않는다. 모델 재로딩 동안 코치는 일시 중단된다.
   예상 시간은 현재 호스트의 재로딩 실측 후 제시하며 600초 내 준비 실패 시 복구한다.
5. health의 Haiku/키 설정 여부, embedding/reranker, 백엔드→AI 통신을 확인한다.
   기존 `probe.py coach`는 오프라인 전용이며 대화를 저장하므로 무심코 재실행하지 않는다.
6. **별도 승인된 유료 검증**: 개인정보 없는 짧은 질문으로 생성 1회 확인 후 멈춰 사용량을
   확인한다. 다음으로 승인된 시연 계정의 2턴·멱등 재시도·기록 조회를 진행한다.
   외부 호출에는 질문·검색 근거가 Anthropic으로 전달되고 AI SQLite 및 Spring DB에 기록된다.
   실제 공급자 호출 수/토큰·시간·예산 상한을 적용 승인서에 고정한다.
   새 질문은 새 Idempotency-Key, 같은 요청 재시도만 같은 키를 사용한다.

## 실패 시 복구

새 코치를 중지하고 보존한 런처/설정과 기존 digest를 복구한다. 이번에 설치한 drop-in만
제거하고 daemon-reload 후 기본 Compose로 coach를 재생성한다. base Compose에서 키 mount가
사라지고 런처가 키를 비우므로 유료 경로가 꺼진다. service token, 모델, 색인, SQLite,
기존 대화와 PostgreSQL은 삭제하거나 되돌리지 않는다. health·검색 경로를 재확인한다.
이미 발생한 유료 호출/데이터 전송은 되돌릴 수 없다.

## 운영 승인 범위

- A: 기존 AI 서버에 키 파일·런처·Compose override·systemd drop-in 설치 및 코치만 재생성.
- B: 호출 수·토큰·비용 상한을 정한 Haiku 유료 검증과 테스트 대화 저장.
- C: 시연 계정으로 앱 2턴 및 이력·멱등 검증. 실제 계정 E2E는 별도 승인.

이번 작업에서는 A/B/C를 실행하지 않는다. 키 재발급은 하지 않고 사용자가 지정한 원본을
적용 시 사용한다. 실제 키를 테스트에 읽거나 복사하지 않는다.
