# V5 → latest 최초 배포 실행안 (2026-11-08 KST)

이 문서는 **실행하지 않은 승인용 실행안**이다. 신규 SSM 문서/호스트 도구는 제안 파일이며 AWS에 설치되지 않았다.
격리 DB 성공과 실제 AWS 적용 승인을 구분한다. 실 배포 판정은 아래 gate를 모두 닫을 때까지 **NO-GO**다.

## 기준과 작업 범위

- 확인한 develop: `d4fe58cbb134efca34d8386e4f276583625ad885`, V18.
- 기존 AWS 이미지 revision: 사용자 제공 `598b261624a35207cc774214ef11257187dd96e8`, SQL V1–V5.
- 기존 manifest digest: 사용자 제공 `sha256:8b678404cdf9db313ac1d17221db619c3bdc647983e2314c2e62f27e90fa45c2`.
- 계정 `494915081306`, 서울 `ap-northeast-2`, 인스턴스 `i-0d71fcf646b7210f2`, RDS `hobbytree-dev-private`, DB `hobbytree`.
- 실제 실행 SHA는 이 브랜치의 검증된 커밋 또는 PR 병합 후 새 커밋이다. 병합하면 새 SHA로 다시 검증한다.
- V1–V18 SQL은 수정하지 않는다. baseline SQL 바이트와 후보 prefix, Flyway CRC32, JAR SQL을 대조한다.
- 기존 SSM v2/V45/CORS 경로와 기존 host verifier는 수정하지 않는다. 새 경로는 `deploy-schema`에 옆으로 설치하는 **별도 승인 대상**이다.
- 기존 [PR #67](https://github.com/Kau-BugFree/hobby-tree-Backend/pull/67)은 Draft이고 충돌 상태다. manifest/동일 Artifact 게시 구조는 P2에서 재사용하되 통째로 병합하지 않는다. SQL 변경 차단을 해제하지 않는다.

## 사전 gate

| Gate | 통과 조건 / 중단 조건 |
|---|---|
| 고정 소스 | 실제 develop HEAD, 검증 커밋, source tree, 후보 OCI revision, JAR hash가 연결되어야 한다. 새 SHA/로컬 코드 변경 시 이전 결과 사용 금지 |
| 이력 | RDS history가 정확히 성공 V1–V5이고 script/CRC가 baseline과 동일. 실패/누락/예상 외 버전이면 중단 |
| 파괴적 변경 | V9 이전 user_locations/location_consent_history가 0행, V11 이전 user_preferences가 0행이어야 자동 경로 허용. 새 데이터가 있으면 담당자 승인 이관/보존 설계를 먼저 한다 |
| 기존 guard | `hobbytree_v45_rollback_guard`가 있으면 중단. 임의 DROP 금지, 별도 후속 DDL/forward migration 검토 필요 |
| 계정 | migration 계정이 기존 객체를 소유하거나 소유 역할 권한이 있어야 함. 앱 계정은 비관리자/비소유자/DDL 불가. 신규 business table·소속 sequence만 권한 부여; Flyway history와 다른 public 객체에는 추가 권한 없음 |
| 다른 쓰기 | backend 외 DB writer, 관리자 작업, 예약 작업을 정지/협의하고 `writers_fenced`를 사실로 확인. 앱 중지만으로 전체 writer 차단을 추정하지 않는다 |
| 호스트 | helper/Compose/validator/systemd 계약 hash, 디스크 3GiB 이상, MemAvailable 512MiB 이상, Redis ID·mount·health 유지 |
| 백업 | **쓰기 정지 이후 생성된 새 V5 수동 스냅샷**이 available. 자동 백업/과거 수동 스냅샷으로 대체 금지 |
| 복구 | DB 복구 담당자, 승인자, 새 RDS 식별자·private subnet group·SG·parameter group·CA·secret 전환 책임자 확정 |
| 전환 설치 | 신규 SSM 문서·hash 고정 host 도구·승인 plan·migration 자격 파일이 검토/승인 후 설치돼 있어야 함. 기존 v2 우회 금지 |
| 시간 | 최소 30–60분의 점검 창을 예약하고 복구 시 연장 가능성을 공지. 이 시간은 실측 SLA가 아님. 모든 phase plan은 2시간 내 만료 |

V9/V11 삭제와 V16 provider/지역/회차 backfill을 격리 fixture로 검증한다. 미상 콘텐츠를 생성하거나 덮어쓰지 않는다.
Flyway는 migration 파일별로 커밋하므로 V9 실패 시 V6–V8은 남을 수 있다. 실제 리허설에서도 이 상황을 검증한다.
[Flyway transaction 문서](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling)를 참고한다.

## 0. 안전한 로컬 재현 (AWS 승인 불필요)

Windows PowerShell 7, Docker Desktop Linux 엔진, Java21, Python3.11 이상, 사전 다운로드한 Gradle 의존성이 필요하다.

```powershell
git fetch origin develop
git rev-parse origin/develop
./gradlew.bat --offline --no-daemon --max-workers=2 spotlessCheck
./scripts/ci/rehearse-upgrade.ps1 -OutputDirectory C:/path/to/local-evidence
```

runner는 digest 고정 PostgreSQL17/Redis를 localhost 임의 포트에만 열고 임시 메모리 DB를 만든다.
V5 → 최신을 먼저 적용한 별도 API DB에서 전체 HTTP/도메인 테스트를 실행하며, Flyway 테스트는 테스트별 독립 DB를 사용한다.
모든 결과 XML, `history.tsv`, setup/test 로그를 남기며 자기 이름으로 만든 두 컨테이너만 제거한다. fixture 비밀은 프로세스 환경에서 생성하고 복구한다.
`UpgradeRehearsalSetup`은 이름이 고정된 localhost DB 외 연결을 거부한다. 운영 URL을 넣지 않는다.

## 1. 읽기 전용 사전 확인 (승인 불필요)

다음은 승인된 operator PC에서 **읽기 전용**으로 실행할 명령이다. 이번 작업에서는 관리 API를 재실행하지 않았다.
Secrets Manager 값을 출력하는 명령은 사용하지 않는다. 기존 점검은 재사용하되 실제 실행 직전 변동 가능한 항목만 확인한다.

```bash
export AWS_REGION=ap-northeast-2 AWS_PAGER=''
aws sts get-caller-identity --query Account --output text
aws rds describe-db-instances --db-instance-identifier hobbytree-dev-private \
  --query 'DBInstances[0].{Status:DBInstanceStatus,Engine:EngineVersion,MultiAZ:MultiAZ,Retention:BackupRetentionPeriod,Subnet:DBSubnetGroup.DBSubnetGroupName,SG:VpcSecurityGroups[*].VpcSecurityGroupId,Parameters:DBParameterGroups[*].DBParameterGroupName}'
aws ssm describe-instance-information --filters Key=InstanceIds,Values=i-0d71fcf646b7210f2 \
  --query 'InstanceInformationList[*].{Id:InstanceId,Ping:PingStatus,Agent:AgentVersion}'
aws ssm describe-document --name HobbyTree-Dev-DeployBackend \
  --query 'Document.{Default:DefaultVersion,Latest:LatestVersion,Status:Status}'
aws elbv2 describe-target-health --target-group-arn arn:aws:elasticloadbalancing:ap-northeast-2:494915081306:targetgroup/hobbytree-backend-tg/07f8122d999a820d
curl --fail --silent --show-error https://api.hobbytreeapp.com/actuator/health
```

호스트에서는 승인된 접근 방식으로 아래 읽기만 수행한다. SSM Run Command 제출도 이번 요청에서는 금지이며 아래를 임의로 제출하지 않는다.

```bash
docker inspect --format '{{.Image}} {{json .RepoDigests}}' "$(docker inspect --format '{{.Image}}' hobbytree-backend-1)"
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' hobbytree-backend-1
docker inspect --format '{{.Id}} {{.State.Health.Status}}' hobbytree-redis-1
df -h /opt /var/lib/docker /var/lib/hobbytree
awk '/MemAvailable/ {print}' /proc/meminfo
sha256sum /opt/hobbytree/scripts/deploy/ec2-release.py /etc/hobbytree/deployment-contract.json
```

DB는 암호를 인수/DSN에 넣지 않고 기존 승인 credential file/서비스를 이용한다. 아래 SELECT를 read-only transaction에서 실행한다.

```sql
BEGIN READ ONLY;
SELECT current_database(), current_user;
SELECT installed_rank,version,script,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT 'user_locations' AS name,count(*) FROM public.user_locations
UNION ALL SELECT 'location_consent_history',count(*) FROM public.location_consent_history
UNION ALL SELECT 'user_preferences',count(*) FROM public.user_preferences;
SELECT conname FROM pg_constraint WHERE conname='hobbytree_v45_rollback_guard';
SELECT schemaname,tablename,tableowner FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');
COMMIT;
```

## 2. 후보 이미지와 ECR (게시 자체 별도 승인)

**ECR 게시·digest 고정·pre-pull을 DB 변경보다 앞에 둔다.** DB가 바뀐 뒤에 이미지 게시/다운로드 문제로 중단이 연장되는 일을 피한다.
검증 커밋을 `RELEASE_SHA`, 기존 manifest digest를 `PREVIOUS_DIGEST`에 넣는다. 이 값은 image ID와 다르다.

```bash
export RELEASE_SHA='<검증된 40자리 SHA>'
export REGISTRY=494915081306.dkr.ecr.ap-northeast-2.amazonaws.com
export IMAGE="$REGISTRY/hobbytree/backend:$RELEASE_SHA"
docker build --platform linux/amd64 --label org.opencontainers.image.revision="$RELEASE_SHA" -t "$IMAGE" .
docker image inspect "$IMAGE" --format '{{.Os}}/{{.Architecture}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
# 로컬/격리 runtime·JAR·SQL 검증 통과 후 게시만 별도로 승인
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$REGISTRY"
docker push "$IMAGE"
aws ecr describe-images --repository-name hobbytree/backend --image-ids imageTag="$RELEASE_SHA" \
  --query 'imageDetails[0].imageDigest' --output text
```

승인한 바로 그 이미지/Artifact를 게시하고 다시 빌드한 다른 이미지를 대신 게시하지 않는다.
OCI revision, package SQL/CRC, jar SHA256, image ID, ECR manifest digest를 publication receipt에 함께 기록한다.
기존 이미지 삭제, lifecycle/IAM 정책 변경은 이 명령에 포함되지 않는다. 기존 ECR 자동화의 환경 승인/OIDC 구조를 우선 재사용한다.
장기 Access Key를 만들지 않는다. 게시 실패·digest/revision 불일치면 DB 작업을 시작하지 않는다.

## 3. 신규 경로 설치 및 plan (AWS 변경 각각 승인 필요)

`deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json`은 **신규 이름** `HobbyTree-Dev-SchemaTransition`으로 설치할 안이다.
기존 SSM v2를 수정하지 않는다. 신규 문서의 실행 역할은 기존 인스턴스/문서로 좁혀야 하며,
호스트 역할의 RDS snapshot describe와 대상 TG register/deregister 권한을 검토한다. 권한이 없으면 중단하고 별도 IAM 승인을 받는다.
이번 작업에서는 IAM 정책을 생성/변경하지 않았다.

승인 후 호스트에 root 소유의 `/opt/hobbytree/scripts/deploy-schema/`를 mode755로 만들고
`ec2-release.py`와 `schema-transition.py`를 mode500으로 설치한다. 기존 경로를 덮어쓰지 않는다.
신규 문서가 검사하는 SHA와 설치 파일 SHA가 같아야 한다. `render-transition-document.py`는 오프라인 JSON 생성만 한다.
실제 문서/호스트 설치·승인 구성은 **미수행 gate**다.

DB owner 자격은 `/run/hobbytree/migration-secrets/{DB_USERNAME,DB_PASSWORD}`에 기존 승인 secret 공급 방식으로 제공한다.
디렉터리는 UID10001/mode500, 파일은 UID10001/mode400, 부모는 root 소유이다. 값은 로그/SSM 인수/plan/저장소로 보내지 않는다.
앱은 기존 `/run/hobbytree/secrets`를 계속 사용한다. `/run`은 재부팅 시 사라지므로 중간 재부팅은 자동 재개가 아닌 복구 절차 대상이다.

```bash
# 승인된 설치 작업에서만 실행. 기존 v2에 update-document 하지 않는다.
aws ssm create-document --name HobbyTree-Dev-SchemaTransition --document-type Command \
  --document-format JSON --content file://deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json

# 완성된 테스트 report는 revision/source_tree/tests/failures/errors/skipped를 포함해야 한다.
python3 scripts/ci/prepare-schema-transition.py --revision "$RELEASE_SHA" \
  --baseline-sha 598b261624a35207cc774214ef11257187dd96e8 \
  --history /review/history.tsv --jar /review/hobbytree.jar --output /review/transition-plan.json \
  --request '<실제 실행 ID-시도>' --candidate '<후보 ECR manifest digest>' --previous '<직전 재확인 digest>' \
  --db-host '<실제 RDS endpoint>' --app-user '<검증한 앱 역할>' \
  --snapshot 'hobbytree-v5-<고유 실행 ID>' --test-report /review/test-summary.json --writers-fenced
sha256sum /review/transition-plan.json
```

plan은 root 보호 `/etc/hobbytree/transitions/<request>.json`에 별도 승인 설치한다. baseline과 target은 실제 이력에서 계산하며 V18을 executor에 고정하지 않는다.
`before_schema`는 실제 V5 이미지 JAR에도 대조되고 `after_schema`는 후보 이미지 JAR에 대조된다.
새 SHA/새 image digest/새 snapshot/기간 만료는 새 검증·plan hash를 요구한다. plan 자체가 사용자의 승인 이력을 대체하지 않는다.

## 4. 쓰기 중지·백업 창 승인 (별도 승인)

아래 SSM 호출은 신규 문서와 plan 설치·검토 완료 이후에만 가능하다. 모든 호출은 **작성만 했으며 실행하지 않았다**.
operator shell에서 `REQUEST`, `PLAN_SHA`, `PARAMETERS`는 비밀이 아닌 승인 식별자만 담는다.

```bash
export REQUEST='<실제 실행 ID-시도>' PLAN_SHA='<승인한 plan 파일 SHA256>'
PARAMETERS=$(printf '{"RequestId":["%s"],"PlanSha256":["%s"],"Phase":["quiesce"],"Approval":["approve-maintenance-and-backup-window"]}' "$REQUEST" "$PLAN_SHA")
aws ssm send-command --document-name HobbyTree-Dev-SchemaTransition --document-version 1 \
  --instance-ids i-0d71fcf646b7210f2 --parameters "$PARAMETERS" --timeout-seconds 120 \
  --max-concurrency 1 --max-errors 0 --query Command.CommandId --output text
```

quiesce는 기존 호스트 잠금·계약·digest·Redis·ALB 검증과 candidate pre-pull 및 DB read-only precheck를 재사용한다.
이후 저널을 먼저 기록하고 systemd 자동 부팅과 Docker restart를 차단하고 ALB target을 deregister/drain한 다음 backend만 정지한다.
Redis는 정지/재생성하지 않는다. `quiesced`가 기록돼야 백업 창이 시작된다.
ALB unhealthy만으로는 전체 target 불량 시 fail-open할 수 있어 충분하지 않다.
[ALB 공식 문서](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-health-checks.html).
예상 영향: 이 시점부터 전체 API 중단. 전체 drain에 몇 분이 추가될 수 있다.

## 5. 신규 V5 스냅샷 생성 (별도 사용자 승인)

```bash
export SNAPSHOT='hobbytree-v5-<plan과 동일한 고유 실행 ID>'
aws rds create-db-snapshot --db-instance-identifier hobbytree-dev-private --db-snapshot-identifier "$SNAPSHOT"
aws rds wait db-snapshot-available --db-snapshot-identifier "$SNAPSHOT"
aws rds describe-db-snapshots --db-snapshot-identifier "$SNAPSHOT" \
  --query 'DBSnapshots[0].{Arn:DBSnapshotArn,Status:Status,Created:SnapshotCreateTime,Instance:DBInstanceIdentifier,Type:SnapshotType}'
```

available 전에는 DB 변경 금지. executor는 수동/동일 RDS/동일 계정·리전/쓰기 정지 이후 시점을 다시 검사한다.
Single-AZ snapshot은 잠시 I/O를 멈출 수 있다. 앱은 이미 중지 상태이며 백업 완료까지 중단이 이어진다.
[RDS snapshot 공식 문서](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateSnapshot.html).

## 6. DB 전환 승인 (앱 교체와 별도 승인)

```bash
PARAMETERS=$(printf '{"RequestId":["%s"],"PlanSha256":["%s"],"Phase":["migrate"],"Approval":["approve-backed-up-schema-change-no-image-rollback"]}' "$REQUEST" "$PLAN_SHA")
aws ssm send-command --document-name HobbyTree-Dev-SchemaTransition --document-version 1 \
  --instance-ids i-0d71fcf646b7210f2 --parameters "$PARAMETERS" --timeout-seconds 120 \
  --max-concurrency 1 --max-errors 0 --query Command.CommandId --output text
```

실행 중인 후보 application을 통해 DB를 바꾸지 않는다. 동일 후보 JAR의 고정 `SchemaMigrationCli`만 독립 컨테이너에서 실행한다.
파일형 owner 자격, verify-full TLS, clean disabled, baseline false, outOfOrder false, lock timeout5초·statement timeout120초를 사용한다.
V5 history·파괴적 데이터·DDL 소유권을 재확인하고 순서대로 migrate한다. business table/sequence 권한을 부여하고
모든 history/CRC/Flyway validate 및 제한 앱 계정의 권한·Hibernate JPA validate가 끝나야 `db-verified`다.
앱의 Flyway는 계속 OFF다. 어떤 오류든 더 진행하지 않고 backend 중지·ALB 제외를 유지한다.

직접 명령 형식은 host 도구의 `migration()`에 고정돼 있다:

```bash
# host 도구가 digest·file mount·DB target을 검증한 뒤 만드는 명령의 핵심.
# operator가 이를 수동 실행하여 SSM/저널을 우회하지 않는다.
java -Xmx256m -Dloader.main=com.hobbytree.hobbytree.deployment.SchemaMigrationCli \
  -cp /app/hobbytree.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  migrate /run/history.tsv '<baseline 버전>'
```

## 7. 후보 앱 교체 승인 (별도 승인)

```bash
PARAMETERS=$(printf '{"RequestId":["%s"],"PlanSha256":["%s"],"Phase":["deploy"],"Approval":["approve-verified-schema-image-activation"]}' "$REQUEST" "$PLAN_SHA")
aws ssm send-command --document-name HobbyTree-Dev-SchemaTransition --document-version 1 \
  --instance-ids i-0d71fcf646b7210f2 --parameters "$PARAMETERS" --timeout-seconds 120 \
  --max-concurrency 1 --max-errors 0 --query Command.CommandId --output text
```

schema·app 권한을 다시 확인하고 이미지 환경설정만 후보 digest로 바꾼다. 기존 systemd 시작 절차를 사용하고
Redis no-recreate를 유지한다. backend Docker health/정확한 image ID 확인 이후 ALB에 재등록하고
기존 helper의 **150초 연속 ALB healthy** 검증 뒤 systemd 자동 부팅을 다시 켜고 `succeeded`를 기록한다.
Health만으로 모든 API를 지원한다고 선언하지 않는다. 다음 주요 API smoke 완료 여부를 release receipt에 따로 남긴다.

```bash
aws ssm get-command-invocation --command-id '<각 단계 CommandId>' --instance-id i-0d71fcf646b7210f2 \
  --query '{Status:Status,Code:ResponseCode}'
curl --fail --silent --show-error https://api.hobbytreeapp.com/actuator/health
curl --fail --silent --show-error https://api.hobbytreeapp.com/api/v1/onboarding/options
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' https://api.hobbytreeapp.com/api/v1/users/me
```

마지막 요청은 JWT 없이 401이어야 한다. 승인한 테스트 계정으로 로그인→온보딩→회원정보→취미 목록→나무 심기→단계별 활동→활동 상세→체험 목록/상세를 검사한다.
인증 smoke는 파일형 credential/client를 사용하고 token/secret/Authorization을 console·공용 CI artifact에 출력하지 않는다.
성공 응답의 API code와 nullable/빈 배열을 함께 검사한다. 404/empty는 콘텐츠 부재와 라우트 미구현을 구분한다.

## 8. 실패·취소·재부팅 복구 (새 승인 필요)

- preflight 실패로 쓰기 차단 전에 끝났으면 기존 API 영향 없음. 생성된 실패 요청 디렉터리는 재사용하지 않는다.
- quiesce 이후~첫 DB 변경 이전: history가 정확히 V5임을 재검증한 경우에만 별도 승인으로 기존 digest/재시작 정책/systemd/ALB를 원복할 수 있다.
- **DB 변경을 한 번이라도 시도했으면 이미지 단독 롤백 금지.** V6–V8 등의 부분 적용이나 V9/V11 데이터 삭제가 가능하다.
- DB 변경 성공 후 새 앱 실패: backend 중지/ALB 제외를 유지하고 같은 schema의 검증된 forward-fix 또는 승인한 DB 복구를 선택한다.
- SSM timeout/취소/host 재부팅: 호출을 즉시 재제출하거나 active.json을 지우지 않는다.
  `active.json`, `<request>/transition.json`, `hobbytree-transition-<request>` 컨테이너 존재/상태, 실제 Flyway history를 대조한다.
  Docker client timeout 뒤에도 migration 컨테이너가 실행 중일 수 있다. 실행 중인 DB 트랜잭션을 조사하지 않고 재시도/강제 종료하지 않는다.
- nonterminal 저널은 기존 v2/V45 배포도 차단한다. 운영 담당자만 원인을 해결하고 승인된 복구 기록을 남긴다.
- RDS snapshot 복원은 **기존 DB 덮어쓰기 대신 새 인스턴스**를 만든다.
  private subnet/SG/parameter group, DB endpoint, TLS CA·권한·secret, 새 DB의 정확한 V5 history를 검증한 후 기존 V5 앱을 연결한다.
  원본 RDS는 삭제하지 않는다. 스냅샷 이후 쓰기는 복구 DB에 없으며 실제 손실 범위를 확인해야 한다.
  전체 writer가 backup부터 차단됐다는 사실이 확인될 때만 그 구간의 서비스 쓰기 손실 없음으로 판단한다.

```bash
# 장애 복구가 승인된 뒤 실제 읽어 확보한 값을 사용한다. 지금 실행하지 않는다.
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier 'hobbytree-dev-recovery-<실행ID>' --db-snapshot-identifier "$SNAPSHOT" \
  --db-subnet-group-name '<기존 private subnet group>' --vpc-security-group-ids '<검토한 private SG>' \
  --db-parameter-group-name '<기존 검토 parameter group>' --no-publicly-accessible
aws rds wait db-instance-available --db-instance-identifier 'hobbytree-dev-recovery-<실행ID>'
```

복구 비용·새 endpoint/secret 설정·서비스 중단 시간은 별도 승인 대상이며 실측하지 않았다.
[RDS 복원 공식 문서](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_RestoreFromSnapshot.html).

## 배포 후 기록과 P2

request, source SHA/tree, test-report SHA, image ID, OCI revision, ECR manifest digest, before/after schema hash,
V1–최신 history/CRC, snapshot ARN/시각, 세 phase의 SSM CommandId, Redis ID, Docker/ALB health 관측 시각,
API smoke 결과와 복구 담당자/승인 기록을 묶는다. 비밀과 전체 Docker inspect/환경은 보관하지 않는다.

이번 변경은 develop push에 기존 AWS 없는 Docker/Flyway CI를 자동 실행하도록 확장한다.
ECR 후보 자동 게시·approval environment/OIDC 보호는 PR #67의 동일 Artifact 구조를 최신 develop에 이식한 뒤 별도 검증한다.
schema_changed는 직전 배포 manifest와 후보 SQL 집합을 비교해야 하며 단순 직전 commit diff만으로 판단하지 않는다.
DB 변경 없음일 때만 기존 v2와 호환성 확인된 이미지 자동 복구를 재사용한다. DB 변경 있음은 새 plan/backup/DB/image 승인을 유지한다.
최초 배포 뒤 자동 후보 게시, 승인이력 통합, V19+ 자동 발견·출시 고정(11/11)을 추진한다. 지금 완전 무인 배포를 켜지 않는다.
