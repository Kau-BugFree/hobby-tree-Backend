# Backend 릴리스 검증과 승인형 이미지 게시

`.github/workflows/ecr-push.yml`은 수동 `develop` 실행에서 정확한 Git SHA를 검증하고, 격리 CI에서 합격한 linux/amd64 이미지와 릴리스 manifest를 보관한다. 게시와 EC2 교체는 각각 별도의 보호된 GitHub Environment 승인 대상이다. 운영 DB 마이그레이션의 승인과 실행은 별도 절차다.

## 실행과 증거

- `release_sha`에는 실행 시점 `develop`의 전체 SHA를 넣는다. Workflow event SHA와 실제 checkout HEAD도 같아야 한다.
- `baseline_sha`에는 운영자가 검토한 기적용 SQL 집합을 담은 조상 커밋 전체 SHA를 넣는다. Git baseline은 실제 운영 이력에 대한 운영자의 증거와 대조해야 한다. 검증 runner는 운영 DB에 접속하지 않는다.
- 두 SQL 목록을 Git 원본 바이트로 읽고 숫자 순으로 정렬한다. 프로젝트 명명 규칙은 `V1__...`, `V2__...`처럼 1부터 연속하는 정수 버전이다. 중복, 누락, 이름 변경, 순서 오류, 기준 SQL 원문 변경은 차단한다. PR CI는 별도로 대상 브랜치 SQL도 보존하는지 확인한다.
- Java 21·Gradle·Spotless를 실행하고 격리 PostgreSQL 17에서 기준 집합을 실제 Flyway로 적용한다. 후보로 업그레이드하고 성공 이력 및 기존 checksum을 비교한다. 다시 실행할 때 적용 건수는 0이어야 하며 후보 엔티티를 Hibernate validate로 확인한다.
- 기존 Docker TLS·Health·Redis·권한·메모리·실패 사례와 인증 통합 검사를 재사용한다. 런타임 fixture는 전체 SQL을 적용하고 앱 Flyway는 off다. 이미지의 실제 OCI Revision과 JAR 내 SQL 이름·순서·해시는 Git 원문과 같아야 한다.
- `release.json`에는 기준·후보 SHA, Run/attempt, Flyway 순서와 CRC32, 파일 SHA256, SQL 집합 fingerprint, Image ID, archive SHA256, 검증 항목을 기록한다. 원본 archive와 manifest를 7일 보관한다. GitHub 작업 출력의 manifest hash와 같은 실행의 정확한 Artifact ID를 게시 단계에 전달한다.

검증만 실행하는 예시다. 정확한 후보와 baseline을 먼저 확인한다.

```bash
gh workflow run ecr-push.yml --repo Kau-BugFree/hobby-tree-Backend --ref develop \
  -f release_sha=FULL_DEVELOP_SHA -f baseline_sha=FULL_REVIEWED_ANCESTOR_SHA
```

같은 검증 Run에서 게시까지 진행할 계획이면 처음 dispatch할 때 `publish=true`를 지정하고 환경 승인에서 합격 manifest를 검토한다. 기본값은 검증 전용이다. 검증 전용 Run을 나중에 게시하는 범용 cross-run 승격은 이 변경의 범위에 포함되지 않는다. 게시가 필요한 릴리스는 같은 Run에서 대기하도록 요청해 검증을 중복하지 않는다. 고정 V5 원본의 cross-run 게시는 별도 기존 경로를 사용한다.

## 게시와 EC2 교체

1. 게시 job은 승인 후에도 원본 Artifact, archive hash, manifest hash, SHA, Image ID, SQL을 재검사한다. 자격 증명 취득은 그 이후다. 검증한 archive를 load·tag·push한다. `IMMUTABLE`에 예외가 없는 정확한 ECR 저장소만 허용하고 SHA 태그가 이미 존재하면 중단한다.
2. ECR 응답의 원문 manifest SHA256과 registry digest를 대조한다. Manifest의 config digest는 검증한 Image ID와 같아야 한다. `release-publication.json`에 원본 Run/attempt/Artifact·manifest hash와 게시 digest·고정 URI를 기록한다.
3. 자동 EC2 교체까지 요청하려면 같은 dispatch에 `deploy=true`, `expected_current_digest`, `acknowledge_deployment=true`를 함께 지정한다. 기준·후보 SQL 집합이 정확히 같고 두 digest가 달라야 한다. 별도 환경 승인 후 SSM preflight와 기존 SSM v2를 호출한다. SSM v2가 현재 digest CAS, 후보 Revision, 이전·신규 JAR migration-set 동일성, Redis ID/마운트, journal과 잠금, Docker/ALB Health, rollback을 계속 검사한다.
4. SQL이 추가된 후보는 검증·게시까지만 지원한다. 승인 또는 코드 업데이트를 이유로 이 경로에서 스키마 전환이나 V45 호출을 수행할 수 없다. 운영 Flyway 이력/데이터를 잠금 아래 검증하는 범용 서명·rollback gate는 후속 작업이다.
5. 게시 성공 후 CI 종료·취소·오류가 있으면 태그 및 manifest를 운영자가 대조한다. 동일 태그 재게시나 자동 삭제를 수행하지 않는다. 승인된 복구가 필요하면 기록된 digest와 Revision으로 기존 수동 EC2 경로를 사용하고 현재 digest 및 호환성을 다시 확인한다. SSM 시간 초과 시 명령 ID, 실제 컨테이너와 보호된 journal을 대조한 뒤에만 재시도한다.

## GitHub와 AWS 설정 초안

| 보호 Environment | 전용 역할 제안 | 권한 문서 | Trust 초안 |
| --- | --- | --- | --- |
| `hobbytree-release-publish` | `hobbytree-github-release-push-role` | `deploy/iam/v5-artifact-push-policy.proposed.json` 재사용 | `deploy/iam/release-publish-trust.proposed.json` |
| `hobbytree-release-deploy` | `hobbytree-github-release-deploy-role` | `deploy/iam/github-ec2-deployment-policy.json` 재사용 | `deploy/iam/release-deploy-trust.proposed.json` |

- 각 환경에 Required reviewers, Prevent self-review, 관리자 우회 비활성, Selected branch `develop` 하나를 설정한다. 승인 대상은 해당 Run의 manifest와 digest다. Repository variable `HOBBYTREE_RELEASE_AWS_ENABLED=true`는 설정 검증과 운영 승인 이후에만 적용한다.
- Trust는 기존 GitHub OIDC provider와 실제 immutable repo subject에 정확한 Environment 이름을 붙인 값 및 `sts.amazonaws.com` audience를 제한한다. 브랜치 제한은 Environment와 Workflow 모두에서 검사한다. [GitHub OIDC 환경 제한](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- 게시 역할에는 서울 ECR의 정확한 backend 저장소의 읽기·업로드만 허용한다. 배포 역할에는 정확한 EC2 대상의 preflight/v2 문서 호출 및 상태 조회만 허용한다. V45 권한은 별도 기존 경로에 속한다.
- 설치 전에 ECR 불변성·기존 태그, EC2 현재 digest/Revision, SSM 관리 상태와 문서 버전, 호스트의 root 권한·설치 파일 SHA256, 미완료 journal 및 lock, ALB·Redis 상태를 읽기 전용으로 확인한다. SSM v2 설치가 이미 맞으면 기존 설치를 재사용한다. 승인된 설치가 필요한 경우 동결 스크립트와 문서 해시를 확인하고 기존 [SSM v2 설치 순서](ec2-image-deployment.md)를 따른다.

## 11월 6일 발표용 서버 우선 경로

1. 고정 V5 원본 Run `41959441820`과 Artifact `13384572345`의 만료·유효성을 확인한다. 기존 성공 증거를 사용해 [V5 게시 승인 절차](v5-artifact-ecr-publication.md)로 ECR Manifest Digest를 확보한다. 범용 최신 후보 빌드와 별개다.
2. 운영 RDS의 DB UUID·TLS·사용자/권한·실제 Flyway 성공 이력과 checksum, 백업·복원 조건, 현재 Backend 이미지와 V45 설치 상태를 확인한다. 운영 상태는 현 시점 미확인이다. Flyway history 조회는 전용 읽기 역할 및 TLS 세션으로 수행한다.

```sql
BEGIN READ ONLY;
SELECT current_database(), current_schema(), current_setting('server_version');
SELECT installed_rank, version, script, checksum, success
FROM public.flyway_schema_history ORDER BY installed_rank;
COMMIT;
```

3. 운영 DB 전환을 별도로 승인한 경우에만 [V45 절차](approved-v45-deployment.md)대로 보호 CHECK, 기존 이미지 롤백 가능성, 전용 계정과 Flyway validate를 점검하고 고정 후보의 V4·V5를 적용한다. 실패하면 이미지 교체를 보류하고 원인·이력·복원 조건을 확인한다. Flyway repair나 임의 SQL 편집으로 통과시키지 않는다.
4. DB 이력과 보호 CHECK를 확인한 후 V45 전용 SSM과 오프라인 서명 승인으로 교체한다. 서명 개인키 및 운영 DB 자격 증명은 운영자 관리 영역에 둔다. 이전 이미지가 데이터를 읽을 수 있는 조건이 깨지면 자동 롤백을 강행하지 않는다.
5. 완료 직후 한 번 Docker Health·ALB 대상 Health, 이전 Redis 컨테이너/마운트 보존, Flyway 성공 이력과 app Flyway off, 발표에서 쓸 실제 API의 응답을 확인한다. ALB·ACM·DNS 및 HTTPS 전환은 별도 승인 후 구성하고 인증서 hostname·HTTPS·프론트 호출과 FastAPI 통신을 확인한다.
