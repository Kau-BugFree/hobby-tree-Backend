# 시연용 AI 전용 서버

Issue #158의 승인된 신규 서버 설치 자료입니다. 기존 백엔드 배포나 앱 DB 적재를 실행하지 않습니다.
리소스 생성과 SSM 실행은 수동 작업이며, 이 디렉터리의 추가/병합으로 배포가 시작되지 않습니다.

## 고정된 입력

- AI source: `9364bface2638fb62c54edef6782ea58d5d73df9`
- 이미지 빌드/로컬 검증: PR #156, `c4b1fb7addd97a72408e69bfd09489ae9cea1dc9`
- 서버: 서울 `c7i.xlarge`, 4 vCPU/8 GiB, 암호화 gp3 80 GiB
- 자산: `assets-manifest.json`의 34파일, 7,852,052,666 bytes
- 원본 자산 manifest SHA-256: `16d71f341a57d3163fecd82d108950bd7b4bc61127021f0d903b9827de37d094`
- Git/서버용 LF 정규화 manifest SHA-256: `0c0443db5beaf2b7a3fc247e4088d99757974a7629d6d4a290b0648b20e58eba`
  (줄바꿈만 변경, 34개 경로·크기·자산 해시는 동일)
- Docker Compose v5.7.0 Linux x86_64: SHA-256 `7758048ad38e580f22559603312874fec8bb638d021a9e64182385fc17cc12a2`
  ([공식 릴리스](https://github.com/docker/compose/releases/tag/v5.7.0))

`runtime.env`는 서버에서만 준비하며 `AI_PRIVATE_IP`, `COACH_IMAGE`, `RECOMMENDER_IMAGE` 세 값만 포함합니다.
이미지는 ECR 조회로 확인한 `repository@sha256:...` 주소를 사용합니다. 임의 태그를 사용하지 않습니다.
Compose 실행 파일, 모델, runtime.env, 인증정보와 대화 SQLite는 Git에 넣지 않습니다.

## 새 호스트 설치 순서

1. 승인 범위의 신규 리소스인지 확인합니다. 기존 이름/상태가 있으면 자동 덮어쓰기하지 않습니다.
2. EC2는 IMDSv2 필수, hop limit 1, IPv6 IMDS 비활성화로 생성합니다.
   SG는 기존 백엔드 SG에서 TCP 8000/8003만 허용합니다. SSH/인터넷 수신을 열지 않습니다.
3. 비공개 S3의 지정 release prefix에 allowlist 자산과 배포 번들을 올립니다.
   버킷 소유자를 확인하고 public access block, SSE-S3, versioning, TLS 강제를 적용합니다.
4. SSM으로 `/opt/hobbytree-ai/release`에 번들을 내려받고 각 파일 SHA-256을 검증합니다.
   설치자는 호스트 역할의 ECR pull/S3 read 권한만 사용합니다.
5. `bash prepare-host.sh`를 실행합니다. AL2023 Docker/Python/iptables와 고정 Compose를 설치하고
   모든 자산을 검증합니다. 모델은 read-only, 색인은 새 state에 복사합니다.
   중단된 최초 설치는 이미 완료된 파일의 해시를 확인하고 재개할 수 있습니다.
   기존 `state/coach.sqlite`가 있으면 중단하므로 업데이트 도구로 사용하면 안 됩니다.
6. ECR 인증은 root 전용 임시 디렉터리를 사용하고 작업 후 제거합니다.
   `docker compose --env-file runtime.env -f compose.yml pull --policy always`로 명시적으로 가져옵니다.
   Compose의 `pull_policy: never`는 기동 시 검토되지 않은 다운로드를 방지합니다.
7. `systemd-analyze verify /etc/systemd/system/hobbytree-ai.service` 후
   `systemctl enable --now hobbytree-ai.service`로 기동합니다.
   컨테이너는 IMDS 접근이 차단되며 새 서비스 토큰은 원문을 출력하지 않습니다.
8. 컨테이너 내부에서 `probe.py coach`, `probe.py recommender`를 실행합니다.
   추천 공개 예시는 `/tmp/recommender-request.json`에 넣습니다.

## 검증과 실패 처리

- health만으로 성공 판정하지 않습니다. 실제 embedding/reranker 준비 및 activities/knowledge dense scope,
  추천 4상태, 유효성 오류 422, 코치 후속 질문/두 conversationId/동일 요청 replay/무인증 401을 검사합니다.
- 외부 유료 모델은 비활성화합니다. CPU 레시피 검색은 BM25이며 GPU dense 검색으로 보고하지 않습니다.
- 합성 코치 검증은 새 AI 서버의 SQLite에 검증용 대화를 저장합니다. 앱 PostgreSQL에 쓰지 않습니다.
- private IP 바인딩, SG의 백엔드 전용 수신, 인터넷 포트 차단, 컨테이너 IMDS 차단도 확인합니다.
- 실패하면 새 AI 서비스와 인스턴스를 중지하고 자료를 보존합니다. 자동 삭제하지 않습니다.
  중지 후에도 EBS/S3/ECR 보관 비용은 남습니다. 48시간 비용 예시는 자동 종료 예약이 아닙니다.
- 운영 백엔드의 URL/토큰 주입·배포 및 앱 카탈로그 적재는 별도 적용안과 승인 범위입니다.
  AI 서버 설치 완료를 앱 전체 연동 완료로 간주하지 않습니다.

로컬 검증: `python -m unittest discover -s deploy/ai-server -p 'test_*.py' -v`,
`bash -n deploy/ai-server/prepare-host.sh`, Compose config 및 `./gradlew spotlessCheck`.
설치 코드 변경 시 번들 해시를 다시 기록하고 변경한 파일만 승인 대상 서버에 적용해야 합니다.
