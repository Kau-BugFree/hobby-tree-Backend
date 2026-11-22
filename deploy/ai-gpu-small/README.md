# g6f.large 소형 GPU 후보와 검증

Issue #154 / PR #156. **준비 도구이며 운영 적용·GPU 적재 성공 결과가 아니다.**
기존 `deploy/ai-demo` CPU 측정 결과는 2026-11-11의 별도 이력으로 유지한다.

## 범위와 고정 기준

- 후보는 서울 g6f.large 1대, 2vCPU/8GiB RAM, L4 1/8 약 3GB(조회 2,861MiB), 암호화 gp3 **총 50GiB**다.
- 사용자가 말한 VRAM 4GB는 실측 전 권장치다. 원본 FP32 tensor를 FP16으로 계산한 가중치 합계 약 1.34GiB는 실행 중 최대 사용량이 아니다.
- AI는 현재 배포 계약과 같은 `9364bface2638fb62c54edef6782ea58d5d73df9`를 사용한다. AI main의 다른 변경을 같이 올리지 않는다.
- BGE-m3-ko·KURE-Reranker-nano, 길이 1024, rerank 후보 20, 원본 패키지 버전·모델·색인 지문을 유지한다. 각 모델 batch만 1로 생성하고 공유 잠금으로 GPU 작업을 직렬화한다.
- Qwen/vLLM, GPU 자동 증설, 운영 DB, main-release 실행기, 기존 CPU Compose는 변경하지 않는다. CPU BM25나 규칙 재정렬을 GPU 성공으로 처리하지 않는다.
- 기본 후보는 외부 LLM **비활성**이다. launcher는 `deploy/ai-server/coach-entrypoint.py`의 기존 Secret·Haiku opt-in 보호를 재사용한다. 나중에 기존 Haiku를 활성화할 때 별도 검토한 overlay와 호출 예산이 필요하다.

## 빌드: 원본 워킹 트리·인증값을 이미지에 복사하지 않음

저장소 루트에서 실행한다. `AI_REPOSITORY`는 원본 9364bfa 객체가 있는 로컬 AI clone이며,
출력 경로 `GPU_SOURCE`는 아직 존재하지 않는 임시 경로로 지정한다.

```sh
python deploy/ai-gpu-small/prepare_source.py "$AI_REPOSITORY" "$GPU_SOURCE"
python deploy/ai-gpu-small/verify_source.py "$GPU_SOURCE"
docker build --platform linux/amd64 --build-context ai="$GPU_SOURCE" \
  -f deploy/ai-gpu-small/coach.Dockerfile -t hobbytree/coach-gpu:review .
```

Git blob에서 선택한 app/data/requirements 34파일만 새 경로로 추출한다. 줄바꿈을 변환하지 않는다.
고정 manifest 해시로 검증하므로 파일과 manifest를 함께 바꿔도 거절한다. `.env`, 모델, 세션 DB는 build context에 넣지 않는다.
GPU Dockerfile 전용 `.dockerignore`는 필요한 backend 도구 6개만 허용하고 일반 BE 이미지의 허용 목록은 유지한다.

이미지는 모델을 내장하지 않으며 requirements의 `torch==2.11.0+cu128`을 그대로 사용한다.
로컬/CI는 image build와 GPU 없는 환경에서 `GPU_UNAVAILABLE` 거절까지만 확인한다.
ECR publish는 하지 않는다. 실제 게시 전 최종 merge SHA·image digest·package 취약점·GRID/CUDA 호환성을 다시 검토한다.

## 실행 구성 검증: 시작하지 않음

```sh
python deploy/ai-gpu-small/verify_compose.py
```

이 명령은 합성 경로·합성 digest로 `docker compose config`만 실행한다. 실제 token/config를 읽거나 서비스를 시작하지 않는다.
후보 Compose의 변수는 private IPv4, 검증된 추천/코치 image digest, 새 GPU의 복사된 시험 상태 경로, 읽기 전용 모델 경로, owner-only 시험 토큰 파일이다.

coach는 1.5CPU/6GiB, 추천은 0.5CPU/512MiB이고 swap 확장을 허용하지 않는다. 남은 RAM은 host·driver·Docker 여유다.
GPU는 nvidia device 1개를 요청한다. 기존 AI의 private 주소·state 경로를 그대로 사용하지 않는다.
SG는 기존 BE에서 8000/8003만 허용하는 기존 원칙을 따르며 SSH·AI Swagger·GPU probe의 인터넷 공개를 추가하지 않는다.
g6f에는 GRID 드라이버가 필요하다. 설치 전 AMI 최소 root 크기, GRID 버전/해시, CUDA 12.8 호환 driver, NVIDIA Container Toolkit을 고정한다.
이 PR은 host driver 설치·GPU 생성 스크립트를 자동 실행하지 않는다.

## GPU 실행 시 거절 조건

`gpu_runtime:create_app`은 실제 CUDA tensor 연산과 L4 3GB급 장치를 확인한 뒤 보호된 원본 자산 34파일(7,852,052,666바이트)을 읽기 전용으로 검증한다.
모델 폴더의 추가 파일도 지문을 바꾸므로 거절한다. 실제 두 모델 parameter가 CUDA/FP16인지 확인한다.
활동 162·지식 486·레시피 544888, 세 dense scope와 모델 오류 없음이 모두 필요하다.

공유 GPU 잠금은 최대 10초 기다린다. 모델 실행 뒤 CUDA synchronize를 완료한 다음 잠금을 해제한다.
추론 오류가 생기면 프로필을 실패 상태로 유지한다. upstream이 해당 예외를 잡아 BM25를 만들더라도 요청은 503으로 거절하고 `/health`도 503으로 전환한다.
과부하의 `GPU_BUSY`는 bounded 503이며 정상 GPU를 영구 실패로 표시하지 않는다. 후보를 통과시키려면 시험 중 이 오류도 없어야 한다.

## 나중에 승인받은 후보에서만 실행할 실제 GPU 시험

`/gpu/status`와 `/gpu/search-check`는 서비스 토큰으로 보호된다. POST probe는 검색만 수행하며 Claude·대화 저장 API를 부르지 않는다.
외부 API 키를 열지 않고도 실제 GPU RAG를 측정할 수 있다. 원본 모델/색인과 운영 세션은 별개 복사본을 사용한다.
토큰 파일은 UID10001 소유 0400/0600, 모델은 readonly, 상태 디렉터리는 UID10001만 접근 가능하게 준비한다.
probe 실행 위치는 **새 GPU coach 컨테이너 내부**다. 다음 명령은 현재 실행 허가를 의미하지 않는다.

```sh
python /probe/verify_trial.py /run/secrets/coach_service_token /state/gpu-trial-result.json
```

`/probe`에는 이 디렉터리의 `verify_trial.py`와 `gpu_runtime.py`를 읽기 전용 마운트한다.
loopback 8003만 접속하며 redirect·환경 proxy를 사용하지 않는다. 결과 파일이 이미 있으면 덮어쓰지 않는다.
warmup·요청 실패도 새 결과 파일에 남긴다. 다른 기종으로 자동 재시도하지 않는다.

통과 조건은 다음과 같다.

- 최소 30분·100요청, 검색 3범위, 초기 단일 요청 및 이후 동시2 요청. 긴 허용 입력도 포함한다.
- 모든 요청의 hybrid dense + cross-encoder 진단과 실제 embedding/reranker 호출 증가. LLM 호출 0·오류 0.
- 단일 요청 warm p95 5초 이하, 동시2 요청의 개별 응답 45초 이하.
- GPU allocated/reserved 최대 85% 이하, coach cgroup peak 6GiB 이하, 디스크 여유 10GiB 이상.
- profile/AI revision/자산 수·총 bytes·문서 수 일치. CPU fallback, metadata만 GPU로 표시한 응답, 짧은 시험, 누락 metric은 실패다.

cgroup peak는 file cache를 포함한다. 6GiB 제한 안에서 host RAM·OS 상태도 별도로 확인해야 하며,
probe 결과가 EC2 type·루트 EBS·SG/IAM 설정 자체를 증명하는 것은 아니다. 해당 항목은 승인 전/기동 직후 metadata 점검으로 별도 확인한다.

## 격리 테스트·CI

```sh
PYTHONPATH=deploy/ai-gpu-small:$AI_SOURCE/src/activity-coach/backend \
  python -m unittest discover -s deploy/ai-gpu-small -p 'test_*.py' -v
PYTHONPATH=deploy/ai-gpu-small:$AI_SOURCE/src/activity-coach/backend \
  python deploy/ai-gpu-small/check_upstream_contract.py
```

단위 fixture에는 실제 GPU가 없다. 직렬화, 오류·인증·보고서 위조/누락 거절과 원본 constructor/fingerprint 계약을 검증한다.
PR 전용 `ai-small-gpu-validation.yml`은 contents:read만 사용하며 source contract와 CUDA image build를 실행한다.
AWS/OIDC/ECR 권한·게시·deploy job·운영 Secret·모델 다운로드는 없다. 일반 Docker/Flyway·Spotless는 기존 CI로 확인한다.

## 이후 승인 범위와 비용

2026-11-22 서울 Linux On-Demand 조회: g6f.large $0.24838/시간.
환율 1500원/달러와 VAT10%를 가정하면 4시간 컴퓨팅 약 1639원,
파일럿 10일 컴퓨팅 98358원, 50GiB gp3와 실행 중 공개 IPv4 1개를 포함한 10일 약 102812원이다.
BE/RDS/ALB·보존 디스크·S3/ECR·로그/전송·Claude는 별도다. 저장소·디스크는 정지 후에도 비용이 남는다.

1. 서울 G/VT quota 0→2vCPU 요청. 서버 생성과는 별도다.
2. 후보 1대·암호화 총50GiB·최소 IAM/SG·검증 이미지 게시/설치·자료 복사·최대4시간 시험과 정지.
   정확한 AMI/driver·이미지 digest·재기동 필요 여부·한도를 제시하고 승인받는다. 외부 LLM은 비활성이다.
3. 통과 후 일관된 세션 백업/이전·V32 보호 설정의 AI 주소 변경·필요한 앱 재생성·실패 복구·기존 CPU 정지 유지.
   예상 전환 작업창은 30분(앱 재생성이 필요하면 API 중단 가능)이며 실제 경로 사전 확인 후 확정한다.
4. 기존 Haiku 활성화와 시연 계정 E2E의 운영 DB 쓰기·유료 호출은 횟수/비용을 지정해 별도 승인받는다.

연결 전 실패는 새 GPU 정지·자료/로그 보존이다. 연결 후에는 신규 쓰기를 막고 최신 세션을 일관되게 보존한 뒤,
호환성을 확인해 기존 CPU·이미지·AI 주소 설정으로 복원한다. 오래된 DB 덮어쓰기·RDS 버전 되감기·모델 자동 교체는 하지 않는다.
quota/가용 용량 확보 후 설치·검증 3~6시간은 계획치다. 4시간 시험 한도에 닿으면 정지·보고하고 추가 시간을 자동 승인으로 간주하지 않는다.

공식 사양: https://docs.aws.amazon.com/ec2/latest/instancetypes/ac.html
드라이버: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/nvidia-GRID-driver.html
