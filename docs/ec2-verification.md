# EC2 배포 준비 구현 및 검증 결과

## 인증·Redis 후속 변경 (2026-10-31)

최신 변경·실행 결과·미검증 항목은 [인증 배포 문서](auth-redis-ec2-deployment.md)의 검증 절을 따른다.
아래는 이전 DB 전용 배포 구현의 이력이며 현재 인증·Redis 컨테이너 검증 성공을 의미하지 않는다.


## P2/P3 및 Docker CI 보완 (2026-10-27)

별도 `feat/ec2-docker-validation` 브랜치에 TLS/CA 필수 검사, 컨테이너 진입점,
systemd20초/15분5회 재시도, 베이스 digest lock 및 PR용 실제 Docker workflow를 추가했다.
아래 최초 구현 기록의 JAR 기동 결과를 새 이미지의 컨테이너 검증으로 간주하지 않는다.
현재 로컬 Docker Engine이 없어 새 이미지 빌드/컨테이너 검사는 여전히 미실행이며,
push/PR 금지 조건에 따라 GitHub Actions도 아직 실행하지 않았다.
Workflow는 격리 CI PostgreSQL17.11에서 TLS/app 계정/health/Flyway off와 실패 사례를 검사하도록 구성됐다.
최신 실행 방법과 digest/복구 절차는 [docker-ci.md](docker-ci.md)를 따른다.

## 최초 구현 검증 기록

검증일: 2026-10-27. 기준 develop: `91dc0438176fa3d0dd792374dc8b3faf00115080`.
GitHub에서 PR #4의 closed/merged와 병합 커밋을 확인했다.
원본 로컬 저장소는 병합 전 커밋이어서 변경하지 않고, 별도 최신 develop 체크아웃에 구현했다.

## 변경 파일

| 파일 | 변경 목적 |
| --- | --- |
| Dockerfile | Java 21 JDK/JRE 다단계 빌드, non-root UID 10001, curl healthcheck |
| .dockerignore | 빌드 입력 허용 목록, 호스트 환경/비밀/Git/산출물 제외 |
| compose.ec2.yml | Backend 단일 서비스, amd64, localhost 바인딩, 읽기 전용 마운트, 자원/로그/재시작 제한 |
| src/main/resources/application-ec2.yml | Flyway off, validate, configtree, health, Hikari/Tomcat 제한 |
| build.gradle | Boot 버전과 일치하는 Actuator starter 추가 |
| scripts/fetch-ec2-secrets.py | 호스트 역할로 app Secret 조회, 계정/경로 검증, 보호된 파일 생성, 비밀 로그 차단 |
| scripts/tests/test_fetch_ec2_secrets.py | Secret 입력·계정·출력 경로·오류 메시지 검증 |
| deploy/hobbytree-backend.service | 재부팅 후 Secret 재조회와 Compose 시작 절차 예시 |
| .env.ec2.example | 비밀 없는 설정 템플릿, verify-full TLS JDBC URL |
| .gitignore / .gitattributes | 환경 파일·Python 캐시 제외, Linux 파일 줄바꿈 |
| README.md / docs/ec2-deployment.md | 배포 진입 문서와 실행 절차 |
| docs/ec2-verification.md | 이 결과 기록 |

기존 compose.yml, 기본 application.yml, src/main/java 전체, Flyway migration 전체는
기준 커밋 대비 diff가 없다. Dockerfile/Compose/Flyway를 중복 구현하지 않았으며 기존에는 Backend 이미지/EC2 Compose가 없었다.

## 검증 결과

| 항목 | 결과 |
| --- | --- |
| Microsoft OpenJDK 21.0.9, Gradle wrapper 9.7.1 bootJar | 통과, 실행 JAR 약62.4MiB |
| 기존 Gradle 테스트 | 9개, 실패/오류 0 |
| spotlessCheck | 통과 |
| Secret helper unittest | 6개 통과; AWS 호출은 mock, 실제 Secret 조회 없음 |
| 공식 Docker Compose 2.40.3 config --quiet | 통과; 다운로드 SHA-256 검증 후 데몬 없이 실행 |
| Compose 실제 파싱 결과 | Backend만 존재, linux/amd64, 127.0.0.1, 1GiB, validate, Flyway false |
| Compose Secret 환경변수 | DB_PASSWORD 및 AWS 키/토큰 항목 없음 |
| .env.ec2.example Bash source | 통과; JDBC URL의 &는 따옴표로 보호 |
| Temurin Java 21 JDK/JRE jammy 태그 | Docker Hub 메타데이터에서 linux/amd64 지원 확인 |
| git diff --check 및 원본 코드 보존 검사 | 통과 |

로컬 PostgreSQL **17.11**을 localhost 전용 포트에서 일시 기동해 다음을 확인했다.

- 격리 DB에 기존 기본 프로필로 V1을 적용한 뒤, 별도 app 역할에 업무 테이블 DML/sequence 권한만 부여했다.
- app 역할의 CREATE TABLE과 Flyway history SELECT는 모두 거절됐다.
- 같은 app 역할과 configtree 파일로 ec2 프로필 JAR가 정상 기동했다. Flyway 조회 권한 없이 기동하므로
  앱이 migration을 실행하지 않는 것을 함께 검증했다.
- `/actuator/health`: HTTP 200, `{"status":"UP"}`만 반환.
- `/actuator/env`, `/actuator/configprops`: HTTP 404.
- 업무 테이블 18개 + Flyway history 1개 유지, 적용된 V1 checksum `1798696397` 유지.
- 빈 격리 DB에서는 Hibernate `Schema validation: missing table [activities]`로 기동 실패했다.
  해당 DB의 public 테이블은 0개로, Flyway/DDL이 실행되지 않았다.
- 같은 JVM 제한에서 Windows 유휴 working set 약341MiB를 관찰했다. Linux 컨테이너 상한이나 부하 시험 결과는 아니다.
- 종료 후 테스트 Java/PostgreSQL 프로세스를 멈추고 임시 자격정보 파일을 삭제했다.

## 미실행 및 배포 전 확인

현재 로컬에서 Docker Engine을 사용할 수 없어 **실제 Docker 이미지 빌드와 컨테이너 기동은 미실행**이다.
Compose 파싱과 JAR 기동을 이미지 빌드 성공으로 간주하지 않는다.
Linux UID/GID·마운트 권한·read-only rootfs·cgroup 메모리·로그 회전·systemd 재부팅은 실제 환경 검증이 남았다.

AWS CLI/Secrets Manager 실제 조회, ECR push/pull, 실제 RDS 접속/TLS, EC2 배포·부하 시험도 실행하지 않았다.
실제 Secret의 username/password 키 형식, app 계정 이름, IAM/KMS 권한, RDS endpoint/database와 CA를 배포 전 확인한다.

후속 순서:

1. Docker 사용 가능한 PC/CI에서 amd64 이미지 빌드 및 격리 컨테이너 smoke test.
2. app Secret 키 구조·계정·IAM/KMS·RDS TLS 설정 확인 후, 검증된 이미지의 태그/digest 확정.
3. 별도 배포 승인 후 ECR 전송, EC2 파일 준비·pull·기동·health·재부팅 검증.

실제 비밀을 Git/이미지/보고서에 기록하지 않았다. Git commit/push, PR 생성, AWS 자원 변경, 실제 배포는 수행하지 않았다.
