# EC2 인증·Redis 배포

기준: `feature/12-social-auth`의 `94d2c599276fe95dc92dcc2aea87aab410bee71d`.
Amazon Linux 2023 / t3.small 2GiB, 기존 RDS·ALB·ECR을 유지한다.
아래 운영 명령은 배포 담당자가 검토 후 실행할 절차다. 이 변경 작업에서는 AWS 접근,
실제 Secret 조회, 운영 마이그레이션, 배포, commit/push/PR/merge를 실행하지 않았다.

## 설정과 Secret 계약

기존 `.env.ec2`를 덮어쓰지 않는다. `BACKEND_BIND_IP=0.0.0.0`, 기존 DB URL·CA·ECR 이미지
설정을 유지하고 누락된 항목만 추가한다. EC2 8080은 기존대로 ALB 보안 그룹에서만 접근한다.
Compose의 기본 바인딩은 PC/CI 안전을 위해 127.0.0.1이고 EC2 템플릿은 0.0.0.0이다.

| 설정 | 용도 |
| --- | --- |
| `DB_SECRET_ID`, `DB_APP_USERNAME` | 기존 app DB Secret·계정. 변경 없음 |
| `AUTH_SECRET_ID` | 인증 전용 Secrets Manager Secret 이름 또는 ARN |
| `KAKAO_APP_ID` | 양수인 숫자 앱 ID. REST API 키가 아님 |
| `GOOGLE_CLIENT_ID` | 모바일 webClientId/serverClientId와 일치하는 Web Client ID |
| `REDIS_HOST`, `REDIS_PORT` | `redis`, `6379` 고정 |
| `APP_SECRETS_DIR` | `/run/hobbytree/secrets` |
| `REDIS_SECRETS_DIR` | `/run/hobbytree/redis-secrets`, 앱 디렉터리와 중첩 금지 |

인증 Secret은 JSON 문자열 필드 `JWT_SECRET`, `REDIS_PASSWORD`를 가진다. DB Secret의
`username`, `password` 계약과 관리자 계정 거절은 유지한다. JWT_SECRET은 앱 설정 키이며,
AWS Secret 리소스 이름은 AUTH_SECRET_ID로 별도 지정한다.

- JWT: HS256. 암호학적 난수 최소 64바이트를 **표준 Base64**로 인코딩한 한 줄 값.
  64글자라는 뜻이 아니다. 값의 형식·디코딩 길이는 검사하지만 난수 품질은 생성 시 보장한다.
- Redis: 암호학적 난수로 생성한 URL-safe 문자 `[A-Za-z0-9_-]` 32~128자.
  공백·줄바꿈·따옴표는 허용하지 않는다. 이 제한은 설정 삽입과 전달 오류를 방지한다.
- 카카오 Access Token과 Google ID Token을 SDK에서 받는다. 현재 백엔드는 제공자의
  Client Secret, 카카오 Admin Key, authorization-code 교환용 callback 설정을 사용하지 않는다.
- `.env.ec2`, Docker environment, command/build ARG, JVM 인수에 비밀값을 넣지 않는다.
  AWS 키·토큰·프로필을 컨테이너에 전달하지 않는다. `set -x`, Secret 출력, 파일 cat을 금지한다.

호스트 helper는 인스턴스 역할로 두 Secret을 모두 조회·검증한 후 다음 파일을 생성한다.

| 디렉터리 | 파일 | UID:GID / 모드 |
| --- | --- | --- |
| 앱 전용 | DB_USERNAME, DB_PASSWORD, JWT_SECRET, REDIS_PASSWORD | 10001:10001 / 파일0400, 디렉터리0500 |
| Redis 전용 | REDIS_PASSWORD, users.acl | 999:999 / 파일0400, 디렉터리0500 |

Redis의 ACL에는 비밀번호 SHA-256만 기록된다. 평문 파일은 healthcheck의 표준입력에만
사용한다. `redis-cli --askpass`를 사용해 비밀번호를 argv와 환경변수에 넣지 않는다.
Redis에는 DB/JWT 파일을 마운트하지 않는다. ACL의 키 범위는 `auth:*`이며 이 전용 개발용
인스턴스에는 명령을 넓게 허용한다. 명령별 최소 권한 ACL은 후속 강화 항목이다.

기존 필수 configtree import를 유지한다. 파일명 JWT_SECRET/REDIS_PASSWORD가 application.yml의
placeholder로 연결된다. runtime entrypoint는 Java 시작 전에 파일 소유권·모드·형식과
설정 우회를 검사한다. 호스트의 `--check-secrets`는 Redis 양쪽 비밀번호와 ACL도 대조한다.
예상하지 않은 파일이 비밀 디렉터리에 있으면 실패한다. 디렉터리를 다른 용도로 공유하지 않는다.

파일별 교체는 atomic rename이지만 두 디렉터리 전체의 트랜잭션은 아니다. 준비/검사 실패 후
새 앱을 시작하지 않는다. 실행 중인 Redis는 ACL을 자동으로 다시 읽지 않는다.

## Redis 운영 범위와 자원

- 동일 EC2의 별도 컨테이너. 호스트 포트 게시 없음. Redis는 internal `auth` 네트워크만 사용한다.
  백엔드는 기존 외부 통신용 네트워크도 유지하므로 RDS·카카오·Google HTTPS 통신을 막지 않는다.
- 고정된 Redis 7.4 digest, non-root UID999, 읽기 전용 rootfs, ALL capability 제거.
  최초 named volume은 이미지의 `/data` 소유권을 복사한다. CI에서 이미지 UID/GID와 실제 기동을 검사한다.
- AOF + appendfsync everysec, named volume `redis_data`, RDB 주기 저장 off, noeviction.
- 컨테이너 384MiB / swap 추가 없음, 데이터 maxmemory128MiB, CPU0.5, 로그10MiB×3.
  백엔드 1GiB·Heap512MiB와 기존 Hikari/Tomcat 제한을 유지한다. 약640MiB가 호스트에 남는
  초기 예산이며 부하 보장값이 아니다. EC2에서 Gradle/이미지 빌드를 하지 않는다.
- 이 동거 개발 환경은 Redis TLS를 구성하지 않는다. 격리 네트워크·인증을 전제로 하며,
  원격/공유 네트워크로 옮기면 TLS와 서버 인증서 검증을 별도 구현한다.
- Redis는 인증 필수 저장소다. 연결 장애는 500, 세션 유실은 401/재로그인이다.
  `/actuator/health`는 Redis 장애 시 503이므로 ALB에서도 unhealthy가 될 수 있다.
  restart 정책은 unhealthy만으로 프로세스를 재시작하지 않는다.
- AOF everysec도 최근 쓰기 유실 가능성이 있다. 오래된 AOF/백업 복원은 사용한 Refresh Token
  상태를 되돌릴 수 있다. 복원 시 전체 세션을 무효화하는 별도 복구 절차가 필요하다.
  `/run` Secret은 휘발성이며 Redis 데이터 볼륨과 별개다. 운영에서 `down --volumes`를 실행하지 않는다.

현재 세션은 사용자당 하나지만 사용 이력은 재발급마다 기존 토큰의 만료까지 누적된다.
매분 재발급하면 사용자 한 명당 14일에 약2만 키가 남을 수 있다. 메모리, 키 수, evicted_keys,
쓰기 오류, AOF 상태·디스크, 인증 p95, OOMKilled, CPU 크레딧을 관찰한다. noeviction의
쓰기 거절은 로그인/재발급 장애로 이어진다. 재발급 제한은 아직 미구현이다.

## 배포 전 필수 확인과 별도 인증 과제

1. **ALB IP 제한 문제는 미수정**: 현재 AuthFilter는 getRemoteAddr(), forward-headers는 none이다.
   ALB 노드 IP를 공유해 로그인 60초20회 제한이 사용자들을 함께 제한할 수 있다.
   신뢰 프록시 범위를 포함한 실제 IP 복원을 별도 인증 변경으로 해결해야 한다.
   임의 X-Forwarded-For를 신뢰하도록 설정만 켜지 않는다.
2. `V2__update_user_auth_policy.sql`은 변경하지 않았다. 운영 Flyway는 계속 off다.
   migration 계정으로 V2를 먼저 적용해야 하며 기존 WITHDRAWN 사용자가 있으면 V2가 실패한다.
   backfill·백업·호환성 검토 없이 SQL을 우회하거나 앱 계정에 DDL 권한을 주지 않는다.
3. Google 공개키와 kapi.kakao.com에 DNS/HTTPS egress, API 외부 HTTPS, 모바일 Client ID 일치,
   EC2 시간 동기화를 확인한다. 기존 ALB 리스너/대상 그룹/ECR 정책은 이 작업에서 변경하지 않는다.
4. EC2 역할은 기존 DB Secret 외 AUTH_SECRET_ID의 GetSecretValue, 필요 시 해당 KMS 키의
   Decrypt가 필요하다. 권한 준비는 별도 작업이다. GitHub ECR 역할에는 Secret 권한을 추가하지 않는다.
5. JWT 키 교체의 이전 키 허용, Redis 재발급 제한, 로그아웃·복구 API·익명화 작업은 별도 업무다.
   모바일은 Refresh 요청을 단일 실행으로 묶어야 한다. 중복/응답 유실 후 재전송은 세션 폐기를 유발할 수 있다.

## 최초 시작과 재부팅

배포 파일에 기존 파일 외 `deploy/redis.conf`, `scripts/redis-healthcheck.sh`를 포함한다.
기존 env를 보존하면서 위 식별자만 추가하고 신뢰 가능한 관리자 소유·0600으로 관리한다.
검증된 backend 이미지는 기존 ECR에서, Redis는 고정 digest를 사전 pull한다.
Redis Docker Hub 통신이 불가능하면 승인된 별도 이미지 전달 경로를 준비한다.

아래는 **향후 EC2에서 실행할 예시**다. V2·프록시·CI 배포 전 조건을 먼저 해결한다.

```bash
set -euo pipefail
cd /opt/hobbytree
python3 scripts/validate-ec2-config.py --env-file .env.ec2
set -a
. ./.env.ec2
set +a
sudo python3 scripts/fetch-ec2-secrets.py \
  --secret-id "$DB_SECRET_ID" --expected-username "$DB_APP_USERNAME" \
  --auth-secret-id "$AUTH_SECRET_ID" --region "$AWS_REGION" \
  --output-dir "$APP_SECRETS_DIR" --redis-output-dir "$REDIS_SECRETS_DIR"
sudo python3 scripts/validate-ec2-config.py --env-file .env.ec2 --check-secrets
docker compose --env-file .env.ec2 -f compose.ec2.yml config --quiet
# ECR login follows the existing procedure; keep login/pull/up under the same Docker user.
docker compose --env-file .env.ec2 -f compose.ec2.yml pull backend redis
docker compose --env-file .env.ec2 -f compose.ec2.yml up -d --no-build --pull never \
  --no-recreate --wait --wait-timeout 120 redis
docker compose --env-file .env.ec2 -f compose.ec2.yml up -d --no-build --pull never \
  --no-deps --force-recreate --wait --wait-timeout 120 backend
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

systemd는 비밀 없는 설정 검사 → 두 Secret 준비 → 파일 검사 → Redis 유지/기동 → backend 재생성
순서다. 시작 제한 15분5회, 실패 시20초 재시도와 기존 ECR 로그인 방식을 유지한다.
unit은 이미 pull된 이미지만 사용한다. `/run`이 지워진 재부팅도 파일을 다시 준비한다.
ExecStop은 backend만 중지하므로 일반 restart가 Redis를 중단/재생성하지 않는다.
이 unit은 장기 장애 감시기나 무중단 배포 시스템이 아니다.

## 백엔드만 업데이트 / Secret 회전 / 복구

백엔드 업데이트는 새 backend digest만 pull하고 backend만 재생성한다. Redis 설정이나
비밀번호를 함께 바꾸지 않는다. Secret 변경이 없다면 파일을 다시 가져올 필요가 없다.

```bash
docker compose --env-file .env.ec2 -f compose.ec2.yml pull backend
docker compose --env-file .env.ec2 -f compose.ec2.yml up -d --no-build --pull never \
  --no-deps --force-recreate --wait --wait-timeout 120 backend
```

DB/JWT만 회전하고 Redis 비밀번호가 그대로라면 systemd restart로 파일을 준비하고 backend만
재생성할 수 있다. JWT 교체는 기존 토큰을 무효화한다. Redis 비밀번호 회전은 별도 중단 작업이다.

1. backend 중지. 현재 Secret 버전·이미지 digest·롤백 계획 기록. 값은 기록하지 않는다.
2. 승인된 새 Secret을 준비하고 helper와 `--check-secrets` 실행.
3. **명시적으로** Redis만 `up --no-deps --force-recreate --wait ... redis` 실행. 볼륨은 유지.
4. 인증 health 성공 후 backend 시작. 새 SDK 로그인·재발급·보호 API 확인.

일반 systemd 시작은 Redis에 `--no-recreate`를 쓰므로, 비밀번호가 바뀌었는데 회전을 생략하면
health 실패로 멈추며 잘못된 조합으로 backend 배포를 계속하지 않는다. 부분 실패 시 같은 Secret
버전으로 재준비하고 두 서비스를 맞춘다. 오래된 데이터 복원과 단순 컨테이너 재시작을 구분한다.
앱 롤백도 V2 스키마·ANONYMIZED 데이터와 이전 앱 호환성 검증 후 수행한다. DB 자동 역마이그레이션 없음.

## 검증과 현재 한계

Docker Engine29.8.0 / Compose5.5.1에서 실제 인증 통합18개와 Redis 장애·AOF 복구를 검증했다.
최신 결과는 [실제 Docker 검증 기록](auth-redis-docker-verification.md)을 따른다.

- Java21 `test spotlessCheck bootJar` 통과. 62개 중44개 실행, 인증 통합18개는 일반 실행에서 skip.
- Python/Compose 계약 검사는 Windows에서, 실제0400/0500·UID999/10001 검사는 WSL Linux root에서 실행한다.
- 두 workflow는 별도 auth-tests JDK 이미지를 만들고 **실제 DB/Redis가 준비된 뒤**
  AUTH_INTEGRATION_TEST=true로 기존 통합18개를 실행한다. XML report가 비었거나 skip/실패면 CI 실패.
- CI는 운영과 같은 Redis 인증·AOF 구성을 재사용하고 무인증/잘못된 비밀번호 거절, 키 범위,
  실제 Redis 중단 시 health503/로그인500/보호API500, 재기동·컨테이너 교체 후 데이터 유지,
  backend-only 배포 후 Redis ID 유지를 검사한다. OAuth 제공자는 모의 검증이다.
- 테스트 runner는 CI 전용2GiB이며 운영 컨테이너가 아니다. 배포 backend 이미지만 ECR job으로 전달한다.
  테스트 출력에는 토큰이 있을 수 있어 원본 출력을 버리고 고정 결과만 출력한다.
- 로컬 Docker 이미지·인증 통합18개·Redis 장애/영속성 검사는 모두 통과했다.
  실제 GitHub Actions는 commit/push/PR 전이므로 미실행이며 로컬 성공과 구분한다.
- 실제 AL2023 재부팅, RDS V2, 모바일 SDK 토큰, AWS IAM/KMS, 부하·AOF 디스크 검증은 남아 있다.

실제 Secret이나 운영 RDS를 대상으로 테스트를 실행하지 않는다. CI의 volume 삭제는
`hobbytree-ci` 프로젝트에만 해당하며 운영 명령으로 복사하지 않는다.

### Docker 연결 전 검증 이력 (2026-10-31)

아래 표는 이전 이력이다. 현재 완료 결과는 위 실제 Docker 검증 기록을 따른다.

| 검사 | 결과 |
| --- | --- |
| Gradle test / spotlessCheck / bootJar | 성공. 62개 중44개 실행, 인증 통합18개 skip |
| Windows Python + Compose 계약 | 32개 중27개 성공, Linux 전용5개 skip |
| WSL Linux root Python | 30개 성공, Docker Compose CLI 테스트 클래스 skip |
| Linux Secret 파일·소유권·권한 | 실제 임시 `/run/hobbytree/test-*` 경로에서 성공 |
| systemd unit 구문 | 임시 root의 의존 서비스/실행 파일 stub으로 성공. 실제 서비스 기동은 아님 |
| 셸 구문 | redis-healthcheck.sh / docker-entrypoint.sh의 sh -n 성공 |
| Git diff --check | 성공 |
| Java 업무 코드·V2·build.gradle 보존 | 기준 HEAD 대비 변경 없음 |
| Docker 이미지 / 인증 통합 / 실제 Redis 장애 | 엔진 endpoint 부재로 미실행. CI 실행 필요 |

Windows와 WSL 검사를 합쳐 Python 테스트32종을 실행했다. 컨테이너 기반 인증 통합18개는
이 수에 포함하지 않는다. AWS 실제 조회·RDS 마이그레이션·배포와 Git 게시 작업은 수행하지 않았다.

## 변경 파일 목록

아래 파일만 변경/추가했으며 스테이징·커밋하지 않았다.

- `.env.ec2.example`
- `.github/workflows/docker-validation.yml`
- `.github/workflows/ecr-push.yml`
- `Dockerfile`
- `README.md`
- `compose.ci.yml`
- `compose.ec2.yml`
- `deploy/hobbytree-backend.service`
- `deploy/redis.conf`
- `docs/auth-redis-ec2-deployment.md`
- `docs/docker-ci.md`
- `docs/ec2-deployment.md`
- `docs/ec2-verification.md`
- `docs/ecr-push.md`
- `docs/social-login.md`
- `scripts/ci/diagnose-runtime.py`
- `scripts/ci/prepare-fixtures.py`
- `scripts/ci/verify-auth-deployment.py`
- `scripts/ci/verify-runtime.py`
- `scripts/fetch-ec2-secrets.py`
- `scripts/redis-healthcheck.sh`
- `scripts/tests/test_auth_deployment.py`
- `scripts/tests/test_ci_diagnostics.py`
- `scripts/tests/test_compose_contract.py`
- `scripts/tests/test_fetch_ec2_secrets.py`
- `scripts/tests/test_systemd_unit.py`
- `scripts/tests/test_validate_ec2_config.py`
- `scripts/validate-ec2-config.py`

추가 Docker 검증 파일: `.dockerignore`, `scripts/ci/auth-test-entrypoint.sh`, `docs/auth-redis-docker-verification.md`.
