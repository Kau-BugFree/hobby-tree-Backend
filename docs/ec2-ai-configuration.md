# EC2 추천·코치 설정 파일 계약

이 문서는 #162의 **운영 적용 준비** 범위이다. 코드 병합만으로 AI 호출, 운영 배포 또는 DB 적재가 실행되지 않는다. 프론트 API 연동은 프론트 팀이 로컬에서 진행한다. API 계약은 [14항목 설문·추천](full-questionnaire-recommendation.md), [코치 conversationId](coach-fastapi-integration.md)를 따른다.

## 파일 경로와 동작

| 위치 | 소유자 / 권한 | 역할 |
| --- | --- | --- |
| 호스트 `/etc/hobbytree/ai.properties` | root:root / 0600 | 별도 승인 후 설치하는 지속 설정. 부모 경로도 root 소유이며 group/other 쓰기·심볼릭 링크 금지 |
| 호스트 `/run/hobbytree/secrets/ai.properties` | 10001:10001 / 0400 | 기존 `fetch-ec2-secrets.py`가 기동 전 준비. 기존 앱 Secret 디렉터리는 0500 유지 |
| 컨테이너 `/run/secrets/hobbytree/ai.properties` | 동일, 읽기 전용 mount | `application-ec2.yml`의 선택적 properties import |

기존 Systemd/Compose mount를 그대로 사용한다. DB/JWT/Redis 네 파일과 그 검증은 유지한다. AI 파일이 양쪽에 모두 없으면 기존 환경과 동일하게 추천·코치 클라이언트가 기본 비활성 상태이다. 호스트 파일이 사라졌는데 runtime 파일이 남아 있으면 오래된 설정으로 재기동하지 않고 실패한다.

호스트 파일 전체를 검증한 후 임시 파일의 fsync와 replace로 runtime 파일을 교체한다. 오류 메시지에는 내용·토큰을 포함하지 않는다. 이 원자성은 AI 파일 하나에 대한 것이며, DB/JWT/Redis까지 묶인 여러 파일의 트랜잭션을 뜻하지 않는다. 재시작 시 지속 파일에서 재생성하므로 `/run`이 비는 재부팅에도 대응한다.

## 내용 계약

4096바이트 이하 ASCII, LF 줄바꿈, `key=value` 형식이다. 아래 **9개 키를 모두 한 번씩** 포함한다. 빈 줄과 `#`로 시작하는 주석은 허용한다. 공백 포함 값, escape, 변수 치환, 중복·추가 키는 거절한다. 실제 토큰을 Git, PR, 로그, 환경변수 또는 명령행 인자로 기록하지 않는다.

| 키 | 허용 값 / 시연 기본값 |
| --- | --- |
| `recommendation.ai.enabled` | `true` 또는 `false` |
| `recommendation.ai.base-url` | 승인된 RFC1918 IPv4의 `http://주소:8000` |
| `recommendation.ai.connect-timeout` | `2s` |
| `recommendation.ai.request-timeout` | `10s`, 최대 `30s` |
| `coach.ai.enabled` | `true` 또는 `false` |
| `coach.ai.base-url` | 승인된 RFC1918 IPv4의 `http://주소:8003` |
| `coach.ai.service-token` | AI 서버와 동일한 64–128자리 URL-safe 토큰. 별도로 새 토큰을 생성하면 인증 불일치 발생 |
| `coach.ai.connect-timeout` | `2s` |
| `coach.ai.request-timeout` | `45s`, 최대 `120s` |

timeout은 양의 정수 `ms`/`s` 단위이며 connect ≤ request이다. URL은 경로·인증정보·query·fragment가 없는 origin만 허용하며 끝의 `/`는 가능하다. DNS·public/loopback/link-local IP·HTTPS로 변경할 때는 이 EC2 전용 계약과 네트워크 설계를 함께 검토한다. 비활성 설정도 완전한 파일 형식을 유지한다.

EC2 검증기는 `COACH_AI_*`, `RECOMMENDATION_AI_*` 및 Spring relaxed-binding 별칭을 환경변수에서 거절한다. 컨테이너 환경이나 CORS 전용 env 파일로 토큰을 우회 주입하지 않는다. 로컬 개발 프로필의 기존 설정 방식은 변경하지 않는다.

## 별도 승인 후 적용할 순서

1. #162 병합 SHA를 고정하고 해당 소스의 CI 검증 이미지·호스트 스크립트를 준비한다. 현재/목표 이미지 digest와 두 스크립트(`validate-ec2-config.py`, `fetch-ec2-secrets.py`) checksum 차이를 승인 자료에 기록한다. 자동 게시·배포 workflow를 이 PR에서 추가하거나 실행하지 않는다.
2. **호스트 계약을 먼저 확인한다.** 기존 배포 gate는 validator 등의 hash를 검증한다. 이 PR의 `deploy/ec2-deployment-contract.json`은 변경된 validator의 hash만 갱신한 로컬 산출물이며 운영 호스트에는 자동 설치되지 않는다. 새 validator와 새 이미지, 새 fetch 스크립트를 동일 소스 기준으로 맞추고 승인된 호스트 계약 갱신 절차를 준비한다. hash mismatch를 무시하거나 gate를 비활성화하지 않는다. 기존 gate가 fetch 스크립트 hash를 검사하지 않으므로 승인 자료에서 fetch checksum도 별도로 확인한다.
3. 운영 DB 사전 조회와 migration/카탈로그 계획은 별도 범위로 검토한다. 운영이 V18이라면 V19–V24 전환 및 기존 데이터 중복·FK·성장/활동 참조 영향의 사전 검증이 필요하다. `Flyway off`, `ddl-auto=validate`와 이미지/DB migration-set 검사 모두 유지한다. 최신 이미지에 필요한 schema 없이 배포하지 않는다.
4. 승인된 서버 간 보안 전달 방식으로 코치의 기존 service token을 호스트 파일에 넣는다. 이 PR은 실제 토큰을 조회·복사하지 않는다. 지속 파일은 root 소유 디렉터리에서 임시 파일로 작성해 권한·내용을 검증한 뒤 교체한다. 먼저 양쪽 `enabled=false`로 준비할 수 있다. 토큰 검증이나 진단에 `cat`, shell tracing, SSM stdout을 사용하지 않는다.
5. AI 서버의 실제 health와 백엔드→AI private 통신을 확인한다. 설정의 두 주소는 승인된 AI 인스턴스 private IPv4와 일치해야 한다. 보안그룹은 백엔드에서만 AI 포트를 허용한다. 주소 검증만으로 SG/접근 통제가 대체되지는 않는다.
6. 승인된 이미지·호스트 파일·DB 상태가 일치할 때 백엔드를 중지하고 설정/호스트 계약을 적용한 후 기존 Systemd 절차로 기동한다. AI 설정 파일 준비 → 검증 → backend 재생성 순서를 유지한다. 임의 `docker restart`만으로는 새로운 persistent 파일이 runtime에 반영되지 않는다.
7. health, OpenAPI의 questionnaire/coach 경로, 실제 승인된 시연 계정의 추천·코치 응답을 확인한다. 계정/대화/추천을 저장하는 smoke test도 DB 쓰기 승인 범위에 명시한다. 외부 유료 모델 호출은 포함하지 않는다.

추천을 활성화해도 앱 DB의 취미·활동 카탈로그가 저절로 적재되지는 않는다. 준비된 cooking/instrument/reading 임시 데이터는 별도 dry-run·기존 ID 매핑·중복 검증·보호 조건을 가진 적재 계획이 필요하다. 커피 데이터는 포함하지 않는다.

## 장애와 rollback

- AI 장애 시 현재 API 클라이언트의 명시적 502/503/504 동작을 유지한다. 14항목을 임의의 기존 5개 필드로 줄여 규칙 추천으로 대체하지 않는다.
- AI 기능만 끄려면 지속 파일에서 해당 `enabled=false`를 원자적으로 반영하고 승인된 재기동 절차를 따른다. 토큰과 나머지 필드는 유지한다.
- 구버전 validator는 `ai.properties`가 추가된 Secret 디렉터리를 거절한다. 이미지 단독 downgrade는 안전한 rollback이 아니다. 백엔드 중지 → 지속 AI 파일을 보호된 별도 위치에 보존 → runtime의 **AI 파일만** 제거 → 승인된 구버전 호스트 스크립트·계약·호환 이미지 복원 → 기동 순서를 별도로 승인한다. 새 fetch가 AI 파일을 다시 만들지 않도록 지속 파일의 활성 경로도 함께 정리해야 한다. 기존 DB/JWT/Redis 파일은 삭제하지 않는다.
- DB schema/data rollback은 여기서 자동 수행하지 않는다. 실제 DB migration-set과 호환되는 이미지가 확보되지 않으면 복구 계획을 재검토한다.

## 검증 경계

`scripts/tests/test_ai_config.py`는 가짜 값과 `/run/hobbytree/ai-config-test-*` 임시 경로만 사용하고 AWS 호출을 mock한다. Linux root 테스트는 컨테이너 또는 CI에서 실행한다. `Ec2AiConfigurationTest`는 실제 EC2 YAML import를 읽고 두 configuration record만 바인딩하며 DB·Redis·HTTP client는 기동하지 않는다. 기존 Docker/격리 PostgreSQL·Redis CI는 AI 파일이 없는 환경의 회귀도 확인한다.

이 검증은 운영 배포·실제 토큰 일치·실제 회원 추천/대화 성공을 보증하지 않는다. 해당 항목은 별도 승인된 적용 후 검증해야 한다.
