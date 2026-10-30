# EC2 Docker 배포 준비

인증·Redis 추가 후에는 [인증 배포 절차](auth-redis-ec2-deployment.md)를 함께 따른다.
ALB IP 제한·V2 마이그레이션·미실행 컨테이너 검증은 해당 문서의 배포 전 조건이다.

기준: PR #4 병합 커밋 `91dc0438176fa3d0dd792374dc8b3faf00115080`, Java 21,
Spring Boot 4.1.1, Amazon Linux 2023 x86_64 / t3.small(2GiB), 외부 PostgreSQL 17.11 RDS.
배포·설치·push 명령은 검토 후 운영자가 실행할 절차이며, 이번 준비 작업에서는 실행하지 않는다.

## 구성과 비밀 관리

- 기존 `compose.yml`은 로컬 PostgreSQL 개발 환경 그대로다. EC2는 반드시 `-f compose.ec2.yml`만 사용한다.
  EC2 Compose에는 backend와 비공개 Redis가 있고 PostgreSQL 서비스나 이미지 빌드 단계는 없다.
- `ec2` 프로필에서만 Flyway를 끄고 Hibernate `ddl-auto=validate`를 유지한다. 스키마가 다르면 기동 실패한다.
  V1과 업무 코드는 유지한다. 변경은 migration 계정으로 새 Flyway 버전을 별도 적용한다.
- 런타임은 app 계정만 사용한다. migration 과정에서 app에 스키마 USAGE, 업무 테이블 DML,
  identity/sequence 사용 권한을 부여한다. postgres, RDS 관리자, migration 계정을 앱에서 사용하지 않는다.
- Secrets Manager의 **app 전용 Secret**에 문자열 키 `username`, `password`가 필요하다.
  기존 키 구조가 다르면 값을 출력하지 말고 스크립트 키 매핑을 먼저 맞춘다. migration Secret은 읽지 않는다.
- EC2 호스트 AWS CLI가 인스턴스 역할로 조회한다. 컨테이너에는 AWS 키·토큰·프로필을 전달하지 않는다.
  `fetch-ec2-secrets.py`는 username을 `DB_APP_USERNAME`과 대조하고 postgres/rdsadmin을 거절한다.
  임의 이름의 관리자 권한까지 판별하지 않으므로 기존 DB 계정의 권한은 별도 확인한다.
- 조회한 `DB_USERNAME`, `DB_PASSWORD`를 `/run/hobbytree/secrets`에 저장한다.
  디렉터리 UID/GID 10001:10001, 0500; 파일 0400. 읽기 전용 디렉터리 마운트와 필수 `configtree:` import를 사용한다.
  일반 Compose의 파일 마운트는 호스트 소유권을 유지하므로 호스트에서 권한을 설정한다.
- 비밀은 AWS 응답 메모리와 보호된 런타임 파일에만 둔다. `set -x`, Secret 응답 출력, `cat`,
  비밀번호가 든 `.env`, JVM 인수, Docker build ARG를 사용하지 않는다. 비밀 폴더를 Git/빌드 컨텍스트에 두지 않는다.

## EC2 사전 확인

1. RDS/EC2의 VPC·라우팅·DNS, RDS SG 5432의 소스가 EC2 SG인지 확인한다.
   실제 RDS 연결 성공은 사용자 제공 상태다. 이번 구현에서는 실제 RDS를 조회·변경하지 않는다.
2. `hobbytree-ec2-role`: app Secret ARN 범위의 `secretsmanager:GetSecretValue`가 필요하다.
   고객 관리 KMS 키 사용 시 해당 키의 `kms:Decrypt` 및 키 정책 허용도 필요하다.
3. ECR pull: `ecr:GetAuthorizationToken`은 Resource `*`;
   `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `ecr:BatchCheckLayerAvailability`는
   서울 `hobbytree/backend` 저장소 ARN으로 제한한다. push 권한은 빌드 담당자/CI에만 둔다.
4. ECR API/DKR, 이미지 레이어 S3, Secrets Manager, 필요 시 KMS/SSM으로 HTTPS 통신이 가능해야 한다.
   인터넷 경로가 없으면 VPC 엔드포인트/출구 경로를 별도로 설계한다.
5. Docker Compose v2(`up --wait` 지원), AWS CLI v2, Python 3, curl이 필요하다.
   `command -v docker python3 aws`로 경로를 확인한다. systemd 예시는 `/usr/bin/docker`,
   `/usr/bin/python3`, root PATH의 `aws`를 가정한다. 다르면 unit 경로를 맞춘다.

## PC/CI에서 이미지 빌드

2GiB EC2에서는 Gradle 이미지 빌드를 하지 않는다. Dockerfile은 Java 21 JDK 빌드와 JRE 실행 단계로
분리하며 최종 이미지는 UID 10001이다. `.dockerignore`는 빌드 입력만 허용한다.

```bash
sh ./gradlew --no-daemon test spotlessCheck bootJar
docker buildx build --platform linux/amd64 --load -t hobbytree/backend:COMMIT_SHA .
docker image inspect hobbytree/backend:COMMIT_SHA --format '{{.Architecture}} {{.Config.User}}'
```

Dockerfile은 gradlew 실행 권한을 설정한다. 이미지 안에서는 bootJar만 만들며 테스트는 위에서 별도로 실행한다.
Temurin JDK/JRE는 조회한 manifest digest로 고정했다. 출처와 amd64 digest는 `deploy/base-image-lock.json`에 있다.
런타임 Python3는 JVM 실행 전 TLS 검사에만 쓰고 종료하므로 상주 프로세스가 추가되지 않는다.
apt 패키지 저장소는 snapshot으로 고정하지 않았으므로 기반 digest만으로 전체 빌드의 비트 단위 재현성을 보장하지 않는다.

ECR push 예시(이번에는 미실행):

GitHub에서 업로드할 때는 [develop 수동 OIDC Workflow](ecr-push.md)를 우선 사용한다.
아래는 개발 PC에서 별도 인증한 경우의 수동 예시다.

```bash
REGISTRY=ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com
IMAGE="$REGISTRY/hobbytree/backend:COMMIT_SHA"
# PC에서는 push 권한이 있는 별도 프로필을 사용한다. readonly 프로필로 push하지 않는다.
aws ecr get-login-password --region ap-northeast-2 --profile BUILD_PROFILE \
  | docker login --username AWS --password-stdin "$REGISTRY"
docker tag hobbytree/backend:COMMIT_SHA "$IMAGE"
docker push "$IMAGE"
```

## EC2 설정과 TLS

`/opt/hobbytree`에 `compose.ec2.yml`, `scripts/fetch-ec2-secrets.py`, `scripts/validate-ec2-config.py`,
`deploy/hobbytree-backend.service`, `deploy/redis.conf`, `scripts/redis-healthcheck.sh`, `.env.ec2.example`을 전달한다.
`.env.ec2.example`을 `.env.ec2`로 복사하고 다음 **비밀이 아닌 설정**만 채운다.

| 변수 | 용도 |
| --- | --- |
| BACKEND_IMAGE | 검증된 ECR 태그 또는 digest |
| BACKEND_BIND_IP | 백엔드 포트 바인딩 주소. 기본값은 127.0.0.1이며, ALB 연결 시 EC2에서 0.0.0.0으로 설정 |
| DB_URL | RDS DNS endpoint/database와 TLS 옵션이 포함된 JDBC URL |
| DB_SECRET_ID | app Secret 이름 또는 전체 ARN |
| AUTH_SECRET_ID | JWT_SECRET·REDIS_PASSWORD를 가진 인증 전용 Secret 이름/ARN |
| KAKAO_APP_ID / GOOGLE_CLIENT_ID | 숫자 카카오 앱 ID / Google Web Client ID |
| REDIS_HOST / REDIS_PORT | redis / 6379 |
| REDIS_SECRETS_DIR | `/run/hobbytree/redis-secrets` |
| DB_APP_USERNAME | 이미 분리한 app 계정의 실제 이름 |
| AWS_REGION | ap-northeast-2 |
| APP_SECRETS_DIR | `/run/hobbytree/secrets` (`/run/hobbytree` 아래 하위 경로만 허용) |
| RDS_CA_BUNDLE | `/opt/hobbytree/certs/rds-ca.pem` |


### ALB 연결용 Docker 포트 설정

기본 Docker Compose 설정은 `127.0.0.1:8080`에 바인딩하며,
로컬 환경과 GitHub Actions에서는 기본값을 유지한다.

ALB를 통해 EC2 백엔드에 접근하려면
`/opt/hobbytree/.env.ec2`에 이미 존재하는
`BACKEND_BIND_IP`의 값을 다음과 같이 변경한다.

```dotenv
BACKEND_BIND_IP=0.0.0.0
```

동일한 환경변수를 중복으로 추가하지 않는다.
기존 설정이 없는 경우에만 새로 추가한다.

EC2 보안 그룹 `hobbytree-ec2-sg`의 TCP 8080 인바운드는
`hobbytree-alb-sg`에서 오는 트래픽으로만 제한한다.

ALB 대상 그룹은 HTTP:8080을 사용하며,
Health Check 경로는 `/actuator/health`로 설정한다.


### 외부 HTTPS 구성 (모바일 앱 배포 전 필수)

`BACKEND_BIND_IP=0.0.0.0`은 EC2 호스트의 네트워크 인터페이스에
백엔드 포트 8080을 바인딩하는 설정이며, HTTPS를 활성화하지 않는다.

ALB → EC2 통신은 VPC 내부 HTTP:8080을 사용한다.
EC2 보안 그룹은 ALB 보안 그룹으로부터의 TCP 8080 접근만 허용한다.

#### 현재 개발 환경

- ALB HTTP:80 리스너를 경유하여
  `/actuator/health` 엔드포인트에서 HTTP 200 및
  `{"status":"UP"}` 응답 확인
- HTTPS:443 리스너 및 TLS 인증서 구성은 아직 완료되지 않음
- 서비스 전용 도메인은 아직 확보하지 않음
- HTTPS 구성 전에는 허용된 테스트 IP에서만 HTTP 접근을 사용하며,
  실제 사용자 인증정보나 민감정보를 전송하지 않는다.

#### 모바일 앱 외부 HTTPS 구성 절차

정식 외부 API 사용 전에 다음 작업을 완료한다.

1. 서비스 API용 도메인 확보 (예: api.example.com)
2. AWS Certificate Manager(ACM)에서 해당 도메인의
   공개 TLS 인증서 요청 및 소유권 검증 (ap-northeast-2)
3. ALB에 HTTPS:443 리스너 생성
   - 발급된 ACM 인증서 연결
   - 보안 정책 선택
   - 기존 `hobbytree-backend-tg` 대상 그룹으로 전달
4. ALB HTTP:80 리스너를 HTTPS:443으로
   301 리디렉션하도록 변경
5. ALB 보안 그룹의 HTTPS:443 접근 허용 범위 설정
6. API 도메인 DNS를 ALB로 연결
7. HTTPS 인증서, API 응답, HTTP 리디렉션 검증
8. 모바일 앱의 API Base URL을 HTTPS 주소로 설정

HTTPS 구성 후에도 ALB → EC2 대상 그룹은 HTTP:8080을 유지한다.
EC2의 TCP 8080을 인터넷 전체에 직접 공개하지 않는다.

외부 클라이언트는 처음부터 HTTPS URL로 요청해야 하며,
HTTP 리디렉션만으로 최초 HTTP 요청이 암호화되는 것은 아니다.



### RDS TLS 설정 및 CA 인증서

DB_URL에는 `sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem`을 포함한다.

두 TLS 파라미터만 허용하며 중복, disable/require/prefer/verify-ca, 다른 CA 경로, 자격정보/sslfactory 옵션을 거절한다.

호스트 사전 검사와 컨테이너 진입점에서 동일 검사기를 사용한다. CA 파일 존재·읽기·파싱·CA 여부·유효기간도 검사한다.

Docker Compose의 `config --quiet`만으로 TLS가 검증되는 것은 아니다.

호스트 이름 검사를 위해 RDS endpoint를 IP로 바꾸지 않는다. URL에 사용자명/비밀번호를 넣지 않는다.

Secret에 host/dbname도 있다면 DB_URL과 일치하는지 확인한다. 스크립트는 username/password만 읽는다.

```bash
sudo install -d -m 0755 /opt/hobbytree/certs
sudo curl --fail --silent --show-error --location \
  https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem \
  --output /opt/hobbytree/certs/rds-ca.pem
sudo chmod 0644 /opt/hobbytree/certs/rds-ca.pem
chmod 0600 /opt/hobbytree/.env.ec2
```

CA는 공개 인증서다. Compose는 Secret 디렉터리/인증서가 없으면 빈 경로를 만들지 않고 실패한다.


## 최초 시작 (EC2에서 검토 후 실행)

`.env.ec2`는 관리자가 작성한 신뢰할 수 있는 비밀 없는 파일이어야 한다.

```bash
set -euo pipefail
cd /opt/hobbytree
python3 scripts/validate-ec2-config.py --env-file .env.ec2
set -a
. ./.env.ec2
set +a
sudo python3 scripts/fetch-ec2-secrets.py \
  --secret-id "$DB_SECRET_ID" --expected-username "$DB_APP_USERNAME" \
  --auth-secret-id "$AUTH_SECRET_ID" --redis-output-dir "$REDIS_SECRETS_DIR" \
  --region "$AWS_REGION" --output-dir "$APP_SECRETS_DIR"
sudo python3 scripts/validate-ec2-config.py --env-file .env.ec2 --check-secrets
# Secret 준비와 파일 검사가 성공한 경우에만 계속한다.
REGISTRY="${BACKEND_IMAGE%%/*}"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"
docker compose --env-file .env.ec2 -f compose.ec2.yml config --quiet
docker compose --env-file .env.ec2 -f compose.ec2.yml pull backend redis
docker compose --env-file .env.ec2 -f compose.ec2.yml up -d --no-build --pull never --no-recreate --wait --wait-timeout 120 redis
docker compose --env-file .env.ec2 -f compose.ec2.yml up -d --no-build --pull never --no-deps --force-recreate --wait --wait-timeout 120 backend
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

Docker 그룹 권한이 있는 동일 사용자로 login/pull/up을 실행한다. root로 실행한다면 모두 root로
실행해 인증 파일 위치를 맞춘다. ECR credential helper를 이용하거나 Docker 인증 파일의 접근을 제한한다.
운영 명령에 로컬 `hobbytree-readonly` 프로필을 복사하지 않고 EC2 인스턴스 역할을 사용한다.

health 기대값은 HTTP 200 / `{"status":"UP"}`. DB 연결을 포함하므로 RDS 장애 시 DOWN/503이다.
health 상세와 `/actuator/env`, `/actuator/configprops`는 HTTP로 노출하지 않는다.
인증 브랜치의 SecurityConfig는 health·로그인·재발급 등 명시된 경로 외 인증을 요구한다. ALB 실제 IP와 외부 HTTPS는 인증 배포 문서의 사전 조건이다.

호스트 포트 바인딩은 `BACKEND_BIND_IP` 환경변수로 제어한다.
기본값은 `127.0.0.1`이며, EC2에서 ALB와 연결할 때는
`0.0.0.0`으로 설정한다. 이 경우 EC2 보안 그룹의 TCP 8080
인바운드를 ALB 보안 그룹으로만 제한해야 한다.

현재 HobbyTree 개발 환경은 ALB → EC2:8080 → RDS 구조로 연결되어 있으며,
ALB의 `/actuator/health` 외부 접속 테스트에서 정상 응답을 확인했다.

## 재부팅·회전·업데이트

- `/run`은 재부팅하면 사라진다. Docker restart 정책만으로 Secret을 다시 가져올 수 없다.
  동봉 unit은 부팅 시 Secret 조회 후 Compose를 재생성한다. unit 설치는 이번 작업에서 실행하지 않는다.
- unit 경로를 확인하고 root에서 이미지를 먼저 pull한다. unit은 ECR 로그인/pull을 자동 실행하지 않는다.
  TLS 사전 검사 → Secret 조회 → Compose 순서이며, 실패 시 20초 후 재시도한다.
  15분 안에 시작 시도 5회로 제한한다. 설정/권한 수정 후 `sudo systemctl reset-failed hobbytree-backend`와
  `sudo systemctl start hobbytree-backend`로 복구한다. 이 정책이 장기 unhealthy를 감시하는 것은 아니다.
  설치 예: `sudo install -m 0644 deploy/hobbytree-backend.service /etc/systemd/system/`,
  `sudo systemctl daemon-reload`, `sudo systemctl enable --now hobbytree-backend`.
- Secret 회전 후 재조회와 컨테이너 재생성이 필요하다. configtree는 자동 재읽기를 하지 않는다.
  DB/JWT만 바뀌고 Redis 비밀번호는 같을 때 unit의 `sudo systemctl restart hobbytree-backend`를 사용한다. Redis 비밀번호 회전은 인증 배포 문서의 명시적 Redis 재생성 절차가 필요하다. DB 회전 방식에 따라 짧은 연결 단절이 있을 수 있다.
- 스키마 변경은 새 migration을 먼저 적용하고 app 권한을 검토한다. V1 수정, ddl-auto update/create,
  checksum 우회를 위한 임의 repair를 하지 않는다.
- 업데이트: 새 이미지 pull → BACKEND_IMAGE 갱신 → 재생성 → health 확인.
  단일 컨테이너라 중단 시간이 있다. 실패 시 스키마 호환성을 확인한 이전 digest로 되돌린다.
  DB 데이터/스키마 롤백은 자동 수행하지 않는다.

## 메모리와 로그

상한 1GiB, 예약 512MiB, 추가 swap 없음. heap 512MiB, Metaspace 160MiB, CodeCache/DirectMemory 각64MiB,
stack 512KiB, Tomcat 최대50 thread, Hikari 최대5연결로 시작한다. Redis 추가 전 호스트 OS/Docker/SSM에 약1GiB를 남기던
초기값이며 EC2 부하 시험으로 보장한 값은 아니다. `/tmp` tmpfs 최대64MiB도 컨테이너 메모리에 포함된다.
현재 Redis는 별도384MiB 상한/maxmemory128MiB를 사용하므로 호스트 잔여 예산은 약640MiB다. 대용량 업로드·AI 모델 동거는 별도 설계한다. heap dump는 생성하지 않는다.
표준 출력/오류 로그는 json-file 10MiB×3세대, rootfs는 읽기 전용이다.
`unless-stopped`는 프로세스 종료에 반응하며 **unhealthy 상태만으로 재시작하지 않는다**.
OOM 시 JVM 종료 후 restart 대상이다. `docker stats --no-stream`, health, OOMKilled/RestartCount를 확인한다.
지속 감시/알림은 후속 작업이다.

## 로컬 검증

```bash
sh ./gradlew --no-daemon test spotlessCheck bootJar
sudo python3 -m unittest discover -s scripts/tests -v
docker compose --env-file .env.ec2 -f compose.ec2.yml config --quiet
```

실제 Secret/RDS 대신 격리 PostgreSQL에서 적용된 스키마와 app 권한의 기동, health,
미적용DB의 validate 실패를 확인한다. Docker 사용이 가능해지면 amd64 이미지 빌드, UID/마운트 권한,
read-only rootfs, OOM/재시작, 실제 RDS TLS를 배포 전에 검증한다.

## 공식 문서

- [Spring configtree](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
- [GetSecretValue](https://docs.aws.amazon.com/cli/latest/reference/secretsmanager/get-secret-value.html)
- [ECR EC2 pull](https://docs.aws.amazon.com/AmazonECR/latest/userguide/ECR_on_EC2.html)
- [RDS PostgreSQL TLS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)
