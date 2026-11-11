# AI 시연 로컬 실행과 서버 자원 검증 (#154)

AI `develop` 커밋 `9364bface2638fb62c54edef6782ea58d5d73df9`와 팀 전달
`team-share-2026-11-11.7z`의 실제 추천기·활동코치를 실행한다.
운영 EC2용 설치 스크립트가 아니다. 기존 백엔드 배포·DB·AWS 설정을 변경하지 않는다.

## 준비

- Docker Desktop/Linux amd64, PowerShell 7, Git, tar.
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
docker compose -f deploy/ai-demo/compose.local.yml config --quiet
docker compose -f deploy/ai-demo/compose.local.yml up -d --wait --wait-timeout 300
```

Compose에는 호스트 공개 포트가 없다. 같은 internal 네트워크의 검증 컨테이너에서
`http://recommender:8000`과 `http://coach:8003`으로 접근한다. 코치는
`/backend/coach/chat`에 `X-Coach-Service-Token`이 필요하다. 토큰 파일은 공유 저장소에
올리거나 터미널에 출력하지 않는다. BE 테스트 전용 토큰과만 맞춘다.
종료는 동일 파일의 `docker compose down`이며, 바인드된 임시 상태 파일은 보존된다.
Linux에서 파일을 준비할 때는 상태 디렉터리를 컨테이너 UID 10001이 쓸 수 있게 만든다.

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
