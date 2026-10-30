# 인증·Redis 실제 Docker 검증 결과

검증일: 2026-10-31. 브랜치 `feat/auth-redis-ec2-deploy`, 기준 커밋 `94d2c599`.
앞선 미커밋 변경·신규 파일을 유지한 상태에서 실행했다. commit/push/PR/merge 없음.

## 환경과 격리

- Docker Engine **29.8.0**, Docker Compose **v5.5.1**, linux/amd64.
- Windows named pipe의 sandbox 접근 거부는 확장 실행으로 해결했다. 엔진 장애나 코드 실패가 아니며 기존 컨테이너를 재시작하지 않았다.
- 고유 프로젝트 `hobbytree-ci-auth-20261031`을 사용했다. 기존 개발 프로젝트나 `hobbytree-ci` 자원을 재사용하지 않았다.
- Linux 제어 컨테이너에서 CI 명령을 실행했다. Windows 마운트의 권한 차이를 피하도록 코드·fixture를 전용 Linux named volume에 복사하고 daemon과 같은 절대 경로로 마운트했다.
- 제어 이미지의 Compose가 처음 v5.6.1이어서 공식 v5.5.1 바이너리와 게시 SHA-256을 대조한 뒤 v5.5.1로 맞췄다. 호스트 설치는 변경하지 않았다.
- PostgreSQL17.11·Redis7.4는 저장소의 고정 digest를 사용했다. DB/Redis 네트워크는 internal이다.
- 모의 AWS CLI, 임시 난수 자격증명·테스트 CA만 사용했다. AWS·실제 RDS·운영 Redis·실제 소셜 토큰에 접근하지 않았다.

## 실제 실행 명령

다음 명령은 제어 컨테이너의 Linux 소스 디렉터리에서 실행했다. 호스트에서는
`docker exec hobbytree-ci-auth-20261031-controller ...`로 호출했다.

```bash
docker build --progress=plain --platform linux/amd64 -t hobbytree/backend:ci .
docker build --progress=plain --platform linux/amd64 --target auth-tests -t hobbytree/auth-tests:ci .
python3 scripts/ci/prepare-fixtures.py --output-dir "$CI_FIXTURE_DIR"
python3 scripts/validate-ec2-config.py --env-file "$CI_FIXTURE_DIR/settings" --check-secrets
python3 -m unittest discover -s scripts/tests -q
docker compose -p hobbytree-ci-auth-20261031 -f compose.ec2.yml -f compose.ci.yml config --quiet
docker compose -p hobbytree-ci-auth-20261031 -f compose.ec2.yml -f compose.ci.yml pull postgres redis
docker compose -p hobbytree-ci-auth-20261031 -f compose.ec2.yml -f compose.ci.yml up -d --no-build --wait --wait-timeout 120 postgres redis
docker compose -p hobbytree-ci-auth-20261031 -f compose.ec2.yml -f compose.ci.yml up -d --no-build --wait --wait-timeout 180 backend
python3 scripts/ci/verify-runtime.py
python3 -u scripts/ci/verify-auth-deployment.py
```

`COMPOSE_PROJECT_NAME=hobbytree-ci-auth-20261031`, `HOBBYTREE_CI=1`,
`BACKEND_IMAGE=hobbytree/backend:ci`, `KAKAO_APP_ID=1`,
`GOOGLE_CLIENT_ID=ci.apps.googleusercontent.com`을 사용했다.
APP_SECRETS_DIR/REDIS_SECRETS_DIR/CI_FIXTURE_DIR/RDS_CA_BUNDLE은 전용 fixture volume 아래를 지정했다.
Compose의 인증 runner는 `AUTH_INTEGRATION_TEST=true`, Redis DB1을 사용한다.

검증 스크립트는 실제로 다음을 호출한다.

```bash
docker compose -p "$COMPOSE_PROJECT_NAME" -f compose.ec2.yml -f compose.ci.yml run --rm --no-deps auth-tests
# runner 내부:
./gradlew --offline --no-daemon --max-workers=2 test --tests '*AuthIntegrationTests'
```

잘못된 JWT fixture는 최초 준비 후 추가했으므로 이번에만 동일한 생성 함수
`prepare_invalid_app_secrets()`를 별도 호출했다. CI의 새 fixture 준비에서는 main이 생성한다.
Alpine에 systemd-analyze가 없어 해당 검사 1개는 WSL에서
`python3 -m unittest discover -s scripts/tests -p test_systemd_unit.py -v`로 별도 실행했다.

## 실행 개수

| 검사 | 성공 | 실패 | 미실행 |
| --- | ---: | ---: | ---: |
| Java 단위·MVC 검사 | 44 | 0 | 0 |
| PostgreSQL + Redis 인증 통합 | **18** | **0** | **0** |
| Python 설정·Secret·Compose·systemd 고유 테스트 | 32 | 0 | 0 |
| 시작 거절·TLS·DDL 음성 시나리오 | 15 | 0 | 0 |

Java 이미지 빌드 시 XML은62개 중44개 실행·18개 skip이었다. 이는 통합 서비스 준비 전의
일반 빌드 단계다. 이후 별도 runner에서 **통합18개 모두 실행**했으므로 최종 Java62종 모두 성공이다.
인증 통합18개는 기본 시나리오와 실제 보호 API 보강 후 두 번 실행했고 두 번 모두18/0/0이었다. 표는 고유 테스트 수다.

Docker Python은32개 중31개 성공·systemd1개 skip이었다. 동일한 systemd1개를 WSL에서
실행해 성공했고, Python32종 전체를 검증했다. 미실행 테스트를 통과로 계산하지 않았다.

실제 출력:

```text
AUTH_INTEGRATION_RESULT tests=18 failures=0 errors=0 skipped=0
PASS: actual protected API accepts valid JWT and rejects a different signing key
PASS: protected API returns 500 during actual Redis outage and recovers its AOF session
PASS: Redis auth, fail closed, AOF recovery and backend-only deployment
```

## 필수 항목별 결과

| 항목 | 실제 확인 내용 |
| --- | --- |
| JWT | 실제 보호 API에서 정상 JWT 인증 통과·다른 대칭키 서명 JWT401 거절. HS256 발급, Access30분·Refresh14일, 용도 분리, 잘못된 토큰 거절. Google 공개키·issuer·audience·만료는 모의 JWK 단위 검사 |
| 잘못된 Secret | 잘못된 Base64, 64byte 미만, JWT 파일0444, 소유자root를 실제 컨테이너 시작 전에 거절 |
| Redis 인증 | 올바른 비밀번호 PONG, 무인증 NOAUTH, 잘못된 비밀번호 WRONGPASS, auth:* 외 키 NOPERM |
| Refresh | 해시 저장·재발급·TTL 연장·재사용 세션 폐기·이전 기기와 새 기기 분리·동시 재사용 검사 |
| 장애 | Redis 실제 중단 후 health503·로그인500·유효 JWT 보호 API500. 재시작 후 같은 보호 요청 인증 복구. 통합 테스트에서도 보호 API/재발급 장애500, 세션 유실401 확인 |
| AOF·볼륨 | 임시 키 작성 후 stop/start 복구. Redis force-recreate 후 같은 데이터 볼륨에서 값 복구 |
| 백엔드 단독 배포 | backend만 force-recreate 후 Redis 컨테이너 ID 유지 |
| Secret 권한 | UID10001/999, 파일0400·디렉터리0500, symlink 거절, 양쪽 비밀번호 일치, Redis에 DB/JWT 미마운트 |
| Health·제한 | backend/Redis/PostgreSQL healthy. 최종 backend/Redis 재시작 횟수0, non-root/read-only/메모리/로그 제한 |
| RDS 호환 TLS | 격리 PostgreSQL에 verify-full 실제 연결 및 pg_stat_ssl 확인. 비보안 TLS5종·잘못된 CA·호스트명 불일치 거절 |
| DB 보호 | Flyway 활성화·DDL update 거절, 빈 DB validate 실패와 테이블 미생성, app CREATE 권한 없음 |
| ALB 바인딩 | 실제 Docker 검사에서 CI127.0.0.1:8080, EC2 설정 렌더링에서0.0.0.0:8080 유지. 실제 ALB 미접속 |

음성 시나리오15건은 기대대로 거절된 성공이며 테스트 실패가 아니다.
V1/V2는 일회용 CI DB에만 적용했다. 운영 V2 SQL을 변경하거나 실행하지 않았다.

추가로 UID1001 비root 제어 프로세스에서 호스트 JWT 파일 읽기가 불가능함을 확인한 뒤,
UID10001 backend 안에서만 테스트 JWT를 생성해 정상 인증·다른 키401·실제 Redis 중단500·복구를
검증했다. 키는 컨테이너 밖으로 꺼내지 않았고 JWT는 stdin으로만 전달해 argv·환경변수·로그에 남기지 않았다.

## 이번 추가 수정 파일

- `.dockerignore`, `Dockerfile`, 신규 `scripts/ci/auth-test-entrypoint.sh`: 실제 XML에서18개·실패0·skip0을 강제하고 개수만 출력.
- `scripts/ci/verify-runtime.py`, `verify-auth-deployment.py`, `diagnose-runtime.py`: 고유 COMPOSE_PROJECT_NAME 지원과 검증 프로젝트 라벨 분리,18개 실행 결과 확인. 보호 API의 실제 장애·복구와 비root CI용 runtime 내부 JWT 생성도 추가.
- `scripts/ci/prepare-fixtures.py`: 잘못된 JWT·파일 권한을 실제 컨테이너가 거절하는 fixture 추가.
- `.github/workflows/docker-validation.yml`, `ecr-push.yml`: 임시 컨테이너 정리 대상을 검증 프로젝트 라벨로도 제한.
- 이 결과 문서와 기존 배포·CI 문서 갱신. 기존 인증 Java 업무 코드·build.gradle·V2 SQL은 변경 없음.

기능 테스트 실패는 없었고, 검증 비활성화나 skip으로 우회하지 않았다.

이번 차수의 추가 수정 파일 전체 목록:

- `.dockerignore`
- `Dockerfile`
- `.github/workflows/docker-validation.yml`
- `.github/workflows/ecr-push.yml`
- `scripts/ci/auth-test-entrypoint.sh`
- `scripts/ci/prepare-fixtures.py`
- `scripts/ci/verify-runtime.py`
- `scripts/ci/verify-auth-deployment.py`
- `scripts/ci/diagnose-runtime.py`
- `docs/auth-redis-docker-verification.md`
- `docs/auth-redis-ec2-deployment.md`
- `docs/docker-ci.md`

## 정리와 PR·배포 전 남은 사항

이번 프로젝트의3개 서비스·2개 네트워크·DB/Redis 데이터 볼륨을 정리했고 전용 제어 컨테이너와
코드·fixture 볼륨도 소유 라벨 확인 후 삭제했다. 기존 컨테이너5개의 ID·이름·정지 상태와
기존 PostgreSQL 볼륨 보존을 대조했다. 다른 컨테이너/볼륨 중단·삭제와 global prune은 하지 않았다.
빌드 이미지와 캐시는 재확인용으로 남겼다.

- **PR 생성 전 미해결 로컬 테스트 실패는 없다.** 신규 entrypoint까지 포함해 변경 내용을 검토해야 한다.
- Docker 검증과 ECR validate job 모두 auth-tests 이미지를 만들고 verify-auth-deployment를 실행한다.
  ECR은 검증 성공 후에만 backend 이미지를 export한다. 실제 GitHub Actions는 실행하지 않았으므로 녹색 실행이라고 주장하지 않는다.
- 운영 배포 전 ALB 실제 클라이언트 IP 제한, 실제 SDK 최종 연결, V2 사전 심사·적용, IAM/KMS,
  AL2023 재부팅, t3.small 부하·용량·AOF 복구 정책 검증은 남아 있다.
- AWS·실제 RDS·운영 Redis에 연결하지 않았고, 실제 Secret 조회·출력과 AWS 변경, Git 게시 작업은 없다.
