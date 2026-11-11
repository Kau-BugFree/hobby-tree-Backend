# AI 시연 로컬 실행과 서버 자원 검증 (#154)

AI `develop` 커밋 `9364bface2638fb62c54edef6782ea58d5d73df9`와 팀 전달
`team-share-2026-11-11.7z`의 실제 추천기·활동코치를 실행한다.
운영 EC2용 설치 스크립트가 아니다. 기존 백엔드 배포·DB·AWS 설정을 변경하지 않는다.

## 준비

- Docker Desktop/Linux amd64, PowerShell 7.3 이상, Git, tar.
- AI 저장소에 위 커밋이 존재해야 한다. `main` 대신 해당 커밋을 사용한다.
- 압축을 푼 `activity-coach` 폴더에 models와 backend/var/index가 있어야 한다.
- 이미지 빌드 시 패키지 다운로드만 필요하다. 실행은 외부 네트워크 차단이다.
- 기본 `python:3.12-slim` 이미지를 먼저 준비한다. 코치 이미지 기본 레이어는 digest로 고정했다.

백엔드 저장소 루트에서 실행한다. 실제 경로를 지정하고, 출력 경로는 새로 만든다.
`runtime/`은 Git 제외 대상이다. 기존 디렉터리를 덮어쓰지 않는다.

```powershell
./deploy/ai-demo/prepare-local.ps1 `
  -AiRepository '<AI clone 경로>' `
  -Assets '<압축 해제한 activity-coach 경로>' `
  -OutputDirectory 'runtime/ai-demo'
$env:AI_COACH_STATE_DIR = (Resolve-Path runtime/ai-demo/state).Path
$env:AI_MODELS_DIR = '<압축 해제한 activity-coach/models 절대경로>'
$env:AI_SERVICE_TOKEN_FILE = (Resolve-Path runtime/ai-demo/coach-service-token.local).Path
```

Linux에서 UID 매핑 없이 Docker를 실행한다면, **기동 전에** 다음을 PowerShell에서
실행한다. 경로가 방금 생성한 복사본과 토큰인지 확인한다. 원본 자산에는 적용하지 않는다.
준비 스크립트는 Linux 토큰을 처음부터 `0600`으로 만든다. 이어서 컨테이너 UID 10001만
읽도록 소유권을 맞춘다. Compose의 file secret은 호스트 파일 권한을 유지하므로
YAML의 `uid`/`mode`만 지정하는 것으로 해결되지 않는다. rootless/userns-remap 환경은
호스트의 매핑 UID를 별도로 확인해야 하며 이 절차의 검증 범위 밖이다.

```powershell
sudo chown -R 10001:10001 -- $env:AI_COACH_STATE_DIR
if ($LASTEXITCODE -ne 0) { throw 'State ownership setup failed.' }
sudo chown 10001:10001 -- $env:AI_SERVICE_TOKEN_FILE
if ($LASTEXITCODE -ne 0) { throw 'Token ownership setup failed.' }
sudo chmod 0400 -- $env:AI_SERVICE_TOKEN_FILE
if ($LASTEXITCODE -ne 0) { throw 'Token permission setup failed.' }
```

Windows Docker Desktop에서는 위 Linux 소유권 명령을 실행하지 않는다. 토큰 파일과
출력 디렉터리는 현재 사용자만 접근하는 위치에 두고 Windows ACL을 사용한다.
두 환경 모두 Compose와 같은 UID로 내용 출력 없이 읽기 권한을 확인한 뒤 기동한다.

```powershell
docker run --rm --pull=never --network none --read-only --cap-drop ALL `
  --security-opt no-new-privileges --user 10001:10001 `
  --mount "type=bind,source=$env:AI_SERVICE_TOKEN_FILE,target=/run/secrets/coach_service_token,readonly" `
  hobbytree/coach:9364bfa-cpu-local python -c "from pathlib import Path; assert len(Path('/run/secrets/coach_service_token').read_text().strip()) >= 32; print('Token readable by UID 10001')"
if ($LASTEXITCODE -ne 0) { throw 'Coach token permission check failed.' }
docker compose -f deploy/ai-demo/compose.local.yml config --quiet
docker compose -f deploy/ai-demo/compose.local.yml up -d --wait --wait-timeout 300
```

Compose에는 호스트 공개 포트가 없다. 같은 internal 네트워크의 검증 컨테이너에서
`http://recommender:8000`과 `http://coach:8003`으로 접근한다. 코치는
`/backend/coach/chat`에 `X-Coach-Service-Token`이 필요하다. 토큰 파일은 공유 저장소에
올리거나 터미널에 출력하지 않는다. BE 테스트 전용 토큰과만 맞춘다.

기본 Compose 프로젝트의 내부 네트워크에서 검증 컨테이너를 실행한다.
앞에서 설정한 토큰 파일과 스크립트를 각각 읽기 전용으로 마운트한다.

```powershell
$probeRoot = (Resolve-Path deploy/ai-demo).Path
docker run --rm --pull=never --network hobbytree-ai-demo-local_ai --read-only `
  --cap-drop ALL --security-opt no-new-privileges `
  --mount "type=bind,source=$probeRoot,target=/probe,readonly" `
  --mount "type=bind,source=$env:AI_SERVICE_TOKEN_FILE,target=/run/secrets/coach_service_token,readonly" `
  hobbytree/coach:9364bfa-cpu-local python /probe/verify-compose.py
```

종료는 동일 파일의 `docker compose down`이며, 바인드된 임시 상태 파일은 보존된다.

## 로컬 상태 백업·복구

코치 DB는 `/state/coach.sqlite`, 검색 색인은 `/state/index`에 있다. 실행 중 DB 파일만
복사하면 WAL의 최근 대화를 잃을 수 있다. 아래 절차는 코치를 정상 중지한 뒤 상태
디렉터리 전체를 새 경로에 복사하므로 SQLite의 `-wal`/`-shm`이 남아도 함께 보존한다.
백업은 모델 원본과 분리하며 수 GB의 여유 공간이 필요하다. 토큰은 상태 디렉터리 밖에
있으며 백업에 포함하지 않는다. 동일 환경의 토큰과 이미지 ID를 유지한다.

```powershell
$snapshotPath = Join-Path (Split-Path $env:AI_COACH_STATE_DIR -Parent) ('state-backup-' + (Get-Date -Format yyyyMMdd-HHmmss))
if (Test-Path -LiteralPath $snapshotPath) { throw 'Choose a new backup path.' }
docker compose -f deploy/ai-demo/compose.local.yml stop -t 120 coach
if ($LASTEXITCODE -ne 0) { throw 'Coach must be stopped before backup.' }
Copy-Item -LiteralPath $env:AI_COACH_STATE_DIR -Destination $snapshotPath -Recurse -ErrorAction Stop
docker compose -f deploy/ai-demo/compose.local.yml up -d --wait --wait-timeout 300 coach
```

복구는 동일한 이미지로 코치를 중지하고 백업을 **새 디렉터리**에 복사한다. 기존 상태는
보존한다. Linux에서는 중지된 상태를 읽을 수 있는 관리 권한으로 복사하고, 새 상태
경로의 소유권을 위 UID 10001 절차로 다시 맞춘다. 모델과 토큰 파일은 기존 경로를 쓴다.

```powershell
$restorePath = Join-Path (Split-Path $env:AI_COACH_STATE_DIR -Parent) ('state-restored-' + (Get-Date -Format yyyyMMdd-HHmmss))
if (Test-Path -LiteralPath $restorePath) { throw 'Choose a new restore path.' }
docker compose -f deploy/ai-demo/compose.local.yml stop -t 120 coach
if ($LASTEXITCODE -ne 0) { throw 'Coach must be stopped before restore.' }
Copy-Item -LiteralPath $snapshotPath -Destination $restorePath -Recurse -ErrorAction Stop
$env:AI_COACH_STATE_DIR = (Resolve-Path -LiteralPath $restorePath).Path
# Linux: 위 상태 디렉터리 chown 절차를 새 경로에 적용한 뒤 다음 명령을 실행한다.
docker compose -f deploy/ai-demo/compose.local.yml up -d --wait --wait-timeout 300 coach
```

복원 뒤 위 `verify-compose.py`로 모델 준비·추천·코치·재전송·401을 다시 확인한다.
복원 전 기록한 대화 ID와 requestId 재전송 결과도 확인해 상태 보존을 검증한다.
실패하면 코치를 중지하고 `AI_COACH_STATE_DIR`를 보존한 기존 경로로 되돌려 재기동한다.
이 절차는 로컬 복구만 다루며 운영 설치·복구에는 별도 적용 계획과 승인이 필요하다.

## 원본 데이터·모델 보존

Git archive에도 Windows autocrlf가 적용될 수 있어 `-c core.autocrlf=false`로 추출한다.
전달된 색인은 activities가 LF, knowledge가 CRLF 바이트 기준이다.
`prepare-catalog.py`는 **복사본의 줄바꿈만** 바꿔 원본 manifest 해시와 대조한다.
문자 내용이 달라지면 실패한다. manifest를 새 해시로 바꾸거나 원본 모델을 수정하지 않는다.
수정 가능한 SQLite 색인은 복사본으로 쓰고, 모델은 읽기 전용으로 마운트한다.

CPU 검증은 `torch==2.11.0+cpu`를 사용하며 다른 직접 의존성 버전은 AI 요구사항과 같다.
CPU에서는 활동·지식 벡터와 리랭커가 동작한다. 전달된 CUDA 레시피 벡터의
fingerprint는 CPU 환경과 달라 사용할 수 없으며 레시피는 BM25 경로를 사용한다.
이를 GPU와 같은 검색 품질로 간주하지 않는다.

## 실측 (2026-11-11, 로컬 Docker amd64)

| 대상 | CPU / 제한 메모리 | 시작 | 요청 | 메모리 |
| --- | --- | --- | --- | --- |
| 추천 | 1 / 512MiB | 0.62초 | 4개 상태 각 1~2ms | cgroup 피크 50.9MiB |
| 코치 | 2 / 8GiB | 18.99초 | 추천 0.86초, 주제검색 13.20초, 후속 0.14초 | RSS 최고 약 2.58GiB |
| 코치 | 4 / 8GiB | 17.69초 | 추천 0.82초, 주제검색 8.29초, 후속 0.13초 | RSS 최고 약 2.58GiB |
| 코치 동시 2건 | 4 / 8GiB | 17.69초 | 각각 8.27초 / 4.76초 | RSS 최고 약 2.62GiB |

워밍업·파일 캐시가 포함된 소수 요청 측정이다. EC2 지연 보장이나 부하 테스트 결과는 아니다.
실제 서버에서 같은 요청과 동시 2건을 다시 검증해야 한다. 모델을 worker마다 복제하지 않도록
단일 worker를 사용한다. 서버 후보 검증은 4CPU/8GiB이며 Compose의 코치 제한은
4CPU/6GiB로 두어 8GiB 서버에서 OS와 추천기가 사용할 메모리를 남긴다.

검증한 내용:
- 추천 `recommended`, `browse`, `needs_review`, `no_match`, 잘못된 필드 422.
- 실제 모델 준비: BGE-m3-ko, KURE-Reranker-nano, activities/knowledge dense 준비.
- 자료 162 활동 / 486 코칭 자료 / 544,888 레시피 로드.
- 두 대화의 추천 → 번호 선택 → 단계 안내, 요청 재전송 동일 답변, 토큰 누락 401.
- 외부 생성 모델 키 없음. 설명은 원문 안내(`reference`), 생성 응답 검증은 미실행.
- `test_prepare_catalog.py` 2개 통과, Spotless 통과.
- `prepare-local.ps1` 새 출력 디렉터리에서 전체 실행 통과. Compose 4CPU/6GiB 기동과
  서비스명 HTTP 호출·재전송·401 통과. internal 네트워크와 공개 포트 없음을 확인.
- 토큰 쓰기 구문·Windows 실행, 격리 Linux `0400` 파일의 UID 10001 읽기 및 다른 UID
  접근 거부 통과. Linux PowerShell 준비 스크립트 전체 실행은 미검증이다.
- 코치 정상 중지 → 상태 전체 백업 → 새 경로 복원 → 재기동 통과. 기존 대화/requestId의
  재전송 `replayed=true` 및 답변 SHA-256 일치, 복원 후 전체 HTTP 검증 통과.

`measure-coach.py`와 `measure-recommender.py`는 서버를 자식 프로세스로 실행하고
`/state/measurement.json`에 결과를 남긴다. 컨테이너 실행 시 `--network none`,
`--read-only`, `/tmp` tmpfs, 읽기 전용 `/probe`를 사용한다. 코치에는 읽기 전용
`/models`, 추천기에는 AI examples 폴더를 `/example`로 마운트한다.
`MEASUREMENT_FILE`, `COACH_DATABASE_PATH`를 다르게 지정하면 측정별 기록을 보존한다.
`MEASURE_CONCURRENCY=2`는 별도 두 대화의 동시 검색 검증을 추가한다.

## 서버 제안과 남은 승인

시연 후보는 서울 Linux amd64 **c7i.xlarge (4 vCPU, 8GiB), 암호화 gp3 80GB**다.
현재 2GB 운영 백엔드와 분리하고 동일 VPC에서 백엔드만 AI에 접근하도록 한다.
GPU 레시피 검색이 필수라면 별도 GPU 검증과 비용 승인이 필요하다.

2026-11-11 공개 가격 조회 기준, Linux On-Demand EC2 $0.2016/시간,
80GB gp3 $7.296/월이다. 48시간 컴퓨트는 약 $9.68이며 저장소 기간과 전송·레지스트리·
공인 IPv4가 추가된다. 정확한 기간·계정 할인·세금은 별도다.

공식 가격 출처:
- [EC2 서울 Linux 가격표](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20%28Seoul%29/Linux/index.json)
- [EBS 지역별 가격표](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json)

서버 생성·IAM/보안그룹·이미지/모델 업로드·운영 백엔드 연결·DB 반영·외부 모델 호출은
이 PR이 실행하거나 승인하지 않는다. 별도 적용 계획과 승인이 필요하다.
단일 코치 SQLite는 단일 백엔드 DB와만 연결한다. 서로 다른 DB의 conversationId 충돌을
막기 위해 환경별 상태 경로·서비스 토큰을 분리한다.
