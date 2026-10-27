# Docker PR 검증과 이미지 버전 관리

기능 브랜치: `feat/ec2-docker-validation`. 기준 develop: `91dc0438`.
기존 EC2 배포 파일을 유지하고 P2/P3 보완 및 실제 PR workflow를 추가했다.
이 문서는 준비 절차이며 Git push, PR 생성, 이미지 push, AWS/EC2 변경은 현재 실행하지 않는다.

## PR에서 실행되는 검사

`.github/workflows/docker-validation.yml`은 develop 대상 pull_request와 workflow_dispatch를 지원한다.
기존 Spotless workflow는 그대로 유지한다. 새 job의 이름은 `Docker amd64 and PostgreSQL 17`이다.

1. Java21 테스트/Spotless/bootJar, Python 단위 테스트, systemd unit 구문 검사.
2. 고정된 Temurin digest로 linux/amd64 이미지를 build/load. registry push는 false다.
3. runner의 임시 폴더에 2일짜리 CI CA와 DNS:postgres 서버 인증서, 무작위 DB 비밀번호 생성.
4. 가짜 AWS CLI로 app Secret 파일 생성 절차를 검사. 실 AWS API, credentials, RDS, ECR은 사용하지 않는다.
5. `compose.ec2.yml`에 **CI 전용** `compose.ci.yml`을 합쳐 PostgreSQL17.11과 Backend를 시작한다.
   CI 네트워크는 internal이고 PostgreSQL 포트는 호스트에 공개하지 않는다.
6. **새 CI DB에서만** 기존 V1 SQL을 읽기 전용으로 마운트해 스키마18개를 준비한다.
   Flyway 프로세스를 실행하지 않고 V1 파일도 수정하지 않는다. 실제 RDS의 V1/데이터에는 접근하지 않는다.
7. 별도 ci_app 계정으로 verify-full TLS 연결, validate, health200/UP, 민감 Actuator404 검사.
8. 실제 pg_stat_ssl과 app 연결을 확인하고 app CREATE 권한 부재, Flyway 이력 테이블 부재를 검사한다.
9. 컨테이너 메모리/localhost/non-root/read-only/log 설정과 잘못된 TLS/CA/Flyway/ddl-auto 설정 실패를 검사한다.
   CA는 신뢰하지만 인증서 이름이 다른 DNS alias도 JDBC가 거절하는지 검사한다.
   별도로 준비한 빈 CI DB에서는 실제 Hibernate validate 기동 실패와 테이블0개 유지를 검사한다.
10. 성공/실패 모두 CI 프로젝트 컨테이너와 전용 볼륨을 제거한다.

검사 실패는 job exit1로 PR 검사를 실패시킨다. continue-on-error는 사용하지 않는다.
재시작으로 초기 오류가 숨겨지지 않도록 Backend RestartCount도 0인지 확인한다.
stdout에는 Secret/비밀번호/전체 Docker inspect/원본 앱 로그를 출력하지 않는다.
workflow의 contents 권한은 read이며, checkout 자격정보는 저장하지 않는다.
Workflow summary에는 검증한 source commit과 로컬 image ID를 기록한다. PR의 source commit은 GitHub의 merge preview commit일 수 있다.
PR merge를 실제로 차단하려면 추후 저장소 branch protection/ruleset에서 해당 job을 required check로 선택한다.

## TLS 검사기와 실행 경로

호스트: `python3 scripts/validate-ec2-config.py --env-file .env.ec2`를 Secret 조회 전에 실행한다.
systemd도 같은 순서로 실행한다. 컨테이너 진입점은 같은 검사기를 `--runtime`으로 실행하고 통과해야 Java를 시작한다.
따라서 직접 Compose로 시작해도 잘못된 TLS 설정은 Java/DB 연결 전에 실패한다.

`sslmode=verify-full`, 고정된 컨테이너 sslrootcert 경로만 허용한다. 다른 URL 옵션과 중복 옵션도 거절한다.
추가 접속 옵션이 필요하면 검사 정책과 테스트를 함께 변경한다.
CA는 공개 인증서이며 파일/형식/CA 속성/유효기간을 검사한다. 실제 서버의 chain과 hostname 검증은 pgJDBC가 수행한다.
컨테이너는 ec2 프로필, Flyway false, ddl-auto validate를 요구하며 CLI 설정 인수와 별도 Spring 속성 소스를 거절한다.
JAVA_TOOL_OPTIONS에는 메모리 등 JVM 플래그만 사용하고 -D 시스템 속성을 넣지 않는다.

systemd는 실패 시 20초 후 재시도하며 15분 동안 5회로 제한한다.
권한/설정 수정 후 `systemctl reset-failed` → `systemctl start`로 복구한다.
실제 AL2023 부팅·IAM 장애·재시도 시험은 향후 EC2 검증 항목이다.

## 베이스 digest 기록

`deploy/base-image-lock.json`에 조회일 기준의 공식 Docker Hub 태그 출처,
manifest index digest와 linux/amd64 digest를 기록했다. Dockerfile 두 FROM과 CI PostgreSQL은 index digest를 사용한다.
업데이트 때는 새 digest의 amd64 지원을 확인하고 lock/사용처를 같이 갱신한 뒤 PR 전체 검사를 통과시킨다.
apt 패키지 저장소까지 snapshot 고정한 것은 아니므로 비트 단위 재현 빌드 보장은 아니다.

## 배포 이미지 digest와 복구 기록

CI의 로컬 이미지 ID/`docker image inspect .Id`는 ECR 배포 manifest digest와 다르다.
현재 workflow는 이미지 push를 하지 않으므로 **배포 가능한 ECR digest는 아직 생성/확정하지 않는다**.
추후 승인된 빌드에서 고유 commit 태그로 ECR에 전송한 뒤 ECR의 imageDigest를 읽어 확정한다.

운영자가 추후 실행할 읽기 예시:

```bash
aws ecr describe-images --region ap-northeast-2 --repository-name hobbytree/backend \
  --image-ids imageTag=COMMIT_SHA --query 'imageDetails[0].imageDigest' --output text
```

`.env.ec2`의 BACKEND_IMAGE는 최종적으로 다음 형식을 사용한다.

```text
ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend@sha256:APPROVED_MANIFEST_DIGEST
```

배포 기록에는 다음 **비밀이 아닌 값**을 남긴다: source commit, build/CI URL, image tag/digest,
배포일, 직전 정상 digest, Java/Boot 버전, 적용된 Flyway 버전, health 확인 결과.
환경 파일·DB 비밀번호·Secret 값·AWS 키/토큰을 배포 기록에 복사하지 않는다.

복구 절차(별도 실제 배포 승인 후):

1. 현재 DB 스키마가 직전 앱과 호환되는지 확인한다. V1 수정이나 DB 역마이그레이션을 자동 실행하지 않는다.
2. BACKEND_IMAGE를 기록된 직전 digest로 바꾸고 그 digest를 pull한다.
3. 현재 app Secret/TLS 사전 검사를 통과한 뒤 Compose 재생성 또는 systemd restart.
4. health200, RestartCount/OOMKilled, app 연결을 확인한다. 단일 Backend이므로 짧은 중단이 있을 수 있다.

## GitHub에 올리는 순서

현재 변경은 로컬 기능 브랜치에 있으며 commit/push/PR은 아직 실행하지 않았다.
`git status --short`와 `git diff --check`로 내용을 확인하고, 제공한 파일 목록만 stage한다.
`.env.ec2`, runtime Secret, 테스트 실행 중 생성한 키·비밀번호·DB 데이터는 stage하지 않는다.
`scripts/tests/fixtures/rds-ca-public.pem`은 공개 CA 테스트 fixture이며 비밀 키가 아니다.

검토 후 사용자가 실행할 예시:

```bash
git switch feat/ec2-docker-validation
# 아래 파일 목록 문서대로 git add 후
git diff --cached --check
git commit -m "[Deploy] EC2 배포 검증 및 Docker CI 구성"
git push -u origin feat/ec2-docker-validation
# GitHub Compare & pull request: base=develop, compare=feat/ec2-docker-validation
# gh가 설치된 경우 대안:
gh pr create --base develop --head feat/ec2-docker-validation \
  --title "[Deploy] EC2 배포 검증 및 Docker CI 구성" --body-file PR_BODY_FILE
```

원격 develop이 이후 변경됐으면 별도 기능 브랜치에서 최신 develop과의 차이를 먼저 확인한다.
PR의 실제 Docker 검사 결과가 녹색인지 확인하기 전에는 이미지 빌드/컨테이너 검증이 완료됐다고 판단하지 않는다.
로컬 Docker Engine이 없으면 Java/Python/정적 구문 검사까지만 검증할 수 있다.
