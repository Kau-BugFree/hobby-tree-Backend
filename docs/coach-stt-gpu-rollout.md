# GPU 코치 STT 후보와 운영 적용 경계

Issue #286. Spring API는 #285로 develop에 병합됐으며 이 문서는 **후속 이미지·설정 준비**다.
실제 Google 서비스 계정, 과금 프로젝트, 기기 음성은 이 작업에서 읽거나 호출하지 않는다.

## 소스와 요청 경로

- 기존 코치/RAG 원본 `9364bface2638fb62c54edef6782ea58d5d73df9`를 유지한다.
- AI `e81cfbf88a49e93aa8f702a9e8bf871f01edb6a9`의 `transcribe.py`, `mp4_faststart.py`만 별도 named build context로 내보내고 각각 SHA-256을 검증한다. 미병합 agent/config/main 변경은 가져오지 않는다.
- `google-auth==2.63.1`을 추가한다. 기존 모델·색인·torch 버전은 그대로이며 이미지 안에서 원래 모델 지문 계약도 재검사한다.
- 앱 → Spring `POST /api/v1/coach/transcribe`(JWT, multipart) → GPU `POST /backend/coach/transcribe`(`X-Coach-Service-Token`, raw audio) → Google Speech-to-Text v2 → `{"text":"..."}`.
- STT는 대화를 생성하거나 활동·세션 DB를 쓰지 않는다. 프론트가 변환문을 편집·확인하고 별도 코치 메시지 API로 보내야 대화가 진행된다.
- Google STT는 GPU 모델이나 Haiku가 아니다. 이 변경은 Haiku 모델 선택·활성화·기존 세션을 변경하지 않는다.

## 비활성 기본값과 제한

- `COACH_STT_ENABLED`는 생략/`false`면 인증된 요청에 503을 반환하고 키 로딩과 외부 호출을 하지 않는다. 잘못된 boolean이나 ADC/모델/지역/시간 env override는 기동 시 거절한다.
- Google 설정은 `global`, `long`, `ko-KR`, 호출 최대 30초로 고정한다. GPU 전체 요청 최대 40초, Spring은 45초 권장(허용 40초 초과~60초). Spring 코치 대화의 긴 timeout과 별도다.
- GPU에서 STT 최대 2개 동시 처리, 대기열 없이 초과 429. 업로드 포함 7MiB, audio Content-Type, 빈 파일·초과 파일 거절. 별도 Spring 회원별/전체 제한도 유지한다.
- 원문·오디오·키·Google 오류 본문은 로그에 남기지 않는다. 요청 상태 코드만 기록한다. Google 재시도는 없으며 취소/timeout이 Google의 청구 취소를 보장하지는 않는다.
- 원본 AI 팀은 합성 음성만 검증했다. 기기별 M4A·실제 발음 정확도와 전체 앱 동작은 프론트 실측 전이다.

## 설정 준비

기존 14개 `ai.properties` 키는 그대로 유효하다. STT를 설정할 때만 아래 **두 키를 함께** 추가한다.
기존 14개 값과 서비스 토큰을 보존한다. Spring 활성화에는 `coach.ai.enabled=true`가 필요하다.

```properties
coach.stt.enabled=false
coach.stt.request-timeout=45s
```

`fetch-ec2-secrets.py`가 기존 파일 전달 경로를 재사용한다. 평문 Compose env나 JVM 인수로 STT를 우회 설정하지 않는다.
GPU 후보의 `compose.stt.yml`은 기본 false이며 합성 경로로 config render만 검증한다.
추후 승인 후에만 다음 파일을 설치하고 overlay를 사용한다.

| 위치 | 계약 |
| --- | --- |
| GPU 호스트 `/etc/hobbytree/ai-secrets/google_credentials` | UID/GID 10001, 0400, 일반 파일, 최대 16KiB; 상위 폴더는 root 관리·쓰기 금지 |
| 컨테이너 `/run/secrets/google_credentials` | 읽기 전용 Secret, symlink 거절, 서비스 계정 JSON·project ID 확인 |

실제 전달받은 파일을 저장소·이미지·CI에 복사하지 않는다. 키 권한과 과금 프로젝트는 별도 승인 후 확인한다.
기존 Haiku 운영 overlay와 병합할 때는 그 설정을 보존하고 최종 Compose diff를 다시 검토해야 한다.
현재 검증하는 overlay는 비활성 GPU 후보에 대한 것이며 기존 운영 Compose 적용 완료를 뜻하지 않는다.

## 다음 승인과 검증 계획

1. 병합 SHA 및 동일 이미지 digest 확인 → ECR 게시·취약점 재검사 승인. 이번 PR의 CI는 게시하지 않는다.
2. GPU 임시 기동·후보 내부 검증 승인. 현재/복구 이미지, 모델·색인·세션 보존, Google 키 설치 전 STT 비활성·RAG 계약·메모리 점검. 완료/실패/승인 시간 상한 도달 시 정지.
3. Google 계정·프로젝트 권한 조회, 보호된 키 설치 및 **최대 요청 수/금액을 정한 유료 STT 검증** 별도 승인. 합성 음성 1건부터 응답·오류·시간·메모리를 확인한다. 비용은 확인된 Google 과금 프로젝트/요율로 승인 시 산정하며 현재 무료라고 가정하지 않는다.
4. Spring 릴리즈 승격·배포 및 `coach.stt.enabled=true`, GPU 활성화·공개 재개 승인. 앱의 JWT, multipart 녹음 업로드, 45초 이상의 클라이언트 대기, 편집 후 전송·권한 거부/빈 음성 처리 등을 프론트팀이 확인한다.

AI 이미지 교체 시 코치 모델·색인 재로딩 동안 추천/코치 중단 가능성이 있다. 실제 중단 시간은 기동 점검 후 확정한다.
STT 활성화 실패 시 Spring STT false로 차단하고 GPU STT overlay를 제거하거나 false로 복원한다.
후보 기동/RAG 회귀 실패 시 기존 digest·Compose로 복원하며 세션/모델/색인을 덮어쓰거나 DB를 되돌리지 않는다.
현재 작업에는 서버 기동·키 설치·외부 유료 호출·ECR 게시·운영 배포가 포함되지 않는다.
