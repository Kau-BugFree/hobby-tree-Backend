# V5 → V18 전환 실행안 — RDS 소유권 검토 반영

현재 판정은 **NO-GO**다. 이 문서는 실행 승인이 아니다. AWS 변경, ECR push, SSM 설치/실행, RDS DDL, EC2 변경은 이번 작업에서 수행하지 않는다. 기존 SSM v2/V45 경로 및 Flyway V1–V18 SQL은 그대로 유지한다. V20 develop 통합 실패는 별도 차단 요소다.

## 확인된 사실과 아직 모르는 것

신규 설치 준비 산출물과 운영 명령은 [V18 SSM 설치 절차](v18-ssm-installation.md)를 따른다. 추가 사용자 제공 확인: 테이블·시퀀스 owner=hobbytree_migration, app=hobbytree_app 및 migration SET 정상, 삭제 대상 데이터0, validated V45 guard, default ACL2개 정상, EC2 신규 정책 시뮬레이션과 snapshot describe 성공, 기존 host 파일/Secret UID·mode 확인 완료. 이번 설치 패키지 검증은 이 운영자 증거와 별도로 기록한다.

운영자 제공 읽기 전용 결과: DB hobbytree의 소유자는 postgres이고 public 일반 테이블 20개와 flyway_schema_history의 소유자는 hobbytree_migration이다. migration은 관리자도 postgres MEMBER/SET 역할도 아니다. hobbytree_v45_rollback_guard는 validated CHECK (((listing_status)::text <> 'COMING_SOON'::text))다. 이 결과는 직접 재접속해 확인한 것으로 표시하지 않는다.

DB 소유권과 객체 소유권은 별개다. DB owner 변경이나 migration에 관리자 권한 부여가 필요하지 않다. DB owner=postgres를 유지하고 migration은 자신의 객체 DDL만 담당한다. 테이블·시퀀스 owner와 app 역할/SET 권한은 추가 사용자 증거로 확인됐다. public schema 상세 권한, 전체 LOGIN/상속 inventory, 미래 설치자·실행자 및 snapshot 생성/복구 주체의 권한은 실제 승인 기록과 대조해야 한다. 명칭만으로 rdsadmin 또는 postgres의 실제 AWS 권한을 추정하지 않는다.

## 읽기 전용 사전 점검

[inspect-v5-transition-readonly.sql](../scripts/ci/inspect-v5-transition-readonly.sql)을 검증한 app 역할 변수와 기존 승인 TLS 연결에서 `psql -X -v ON_ERROR_STOP=1 -v app_role='<실제 역할>' -f ...`로 실행한다. SQL은 BEGIN READ ONLY와 timeout을 사용하며 비밀/데이터 행을 출력하지 않는다. 접근 거부나 NULL로 확인할 수 없는 항목은 권한 있는 별도 읽기 전용 운영자 점검으로 보완한다. 계정 권한을 넓혀 검사를 통과시키지 않는다.

필요 권한:

| 객체 | migration | app |
|---|---|---|
| database | CONNECT, CREATE/CONNECT grant option/관리자 권한 불필요 및 거부 | 운영자가 활성화할 때 CONNECT |
| public schema | USAGE + CREATE. 소유권 불필요 | USAGE, CREATE 금지 |
| 기존 public 테이블/history | 현재 코드가 직접 소유권을 요구. ALTER/DROP/DML/history write | 검증한 business 테이블 SELECT/INSERT/UPDATE/DELETE. history write/소유권 금지 |
| 기존 public 시퀀스 | 현재 코드가 직접 소유권 요구. 실제 소유권 추가 확인 | 관련 business 시퀀스 USAGE/SELECT |
| 새 테이블/인덱스/시퀀스/함수/트리거 | public CREATE, sql/plpgsql USAGE 및 대상 테이블 소유권 | V13/V18 함수 실행권/트리거 경로 확인 |
| 역할 전환 | 제한된 app에 대한 SET만 필요, 관리자/DB owner MEMBER/SET 금지 | 관리자 membership 금지 |

V6–V18에는 테이블/인덱스/함수/트리거 생성과 기존 테이블 ALTER/DROP/DML이 있다. V9 파괴적 변경 전 user_locations 및 location_consent_history가 비어 있어야 하고 V11 전 user_preferences도 비어 있어야 한다. 데이터가 있으면 별도 데이터 보존 설계 없이는 중단한다. 기존 객체 충돌/다른 소유자/예상 밖 default ACL도 중단하고 독립 검토한다. public schema GRANT는 migration 코드가 수행하지 않는다. app USAGE 사전 준비도 별도 승인 사항이다.

## 권한 분리와 format3 plan

`prepare-schema-transition.py`는 offline evidence만 생성한다. `--writers-fenced`는 허용하지 않는다. 실제 배포 plan은 기존 evidence/digest/test report와 함께 다음을 요구한다:

- `--database-owner postgres --migration-user hobbytree_migration --app-user '<검증한 app>'`
- 읽기 전용 SQL의 privileged LOGIN inventory 결과 각각에 `--privileged-login-role '<역할>'`. 예시 이름으로 채우지 않는다.
- 신규 snapshot 이름, 실제 RDS endpoint, ECR manifest digest와 이전 digest, 검증 SHA/JAR/history/test report.

plan format3는 `acl_control=external-operator`, `guard_policy=retire-after-snapshot`를 명시하고 format1/2를 거부한다. 후보 image digest/OCI revision/JAR 전체 hash/SQL/host helper 및 root 보호 history/policy 파일을 재검증한다. reviewed privileged LOGIN 목록을 실제 catalog와 정확히 비교하고 migration이 DBowner 또는 해당 특권 역할로 MEMBER/SET 가능한 경우 거부한다. 특권 credential 사용 통제는 운영자의 별도 필수 책임이다.

DB CONNECT ACL 변경은 DB 소유자 또는 별도 검증한 권한 있는 운영자만 수행한다. 해당 credential을 migration 컨테이너/SSM에 공급하지 않는다. 원래 ACL/grant chain을 보호 기록에 보관하고 PUBLIC 및 일반 LOGIN/상속 CONNECT를 차단하되 migration과 검토된 유지보수 역할의 접근만 유지한다. DB ACL 명령은 가능하면 별도 유지보수 DB 연결에서 수행하고 대상 hobbytree의 운영자 세션도 종료한다. 기존 세션은 REVOKE로 종료되지 않으므로 writer/예약 작업 종료 및 별도 승인한 세션 정리가 필요하다. migration은 CONNECT GRANT/REVOKE를 전혀 실행하지 않는다. 원래 ACL 전체 자동 복구나 CASCADE는 금지한다.

## 승인 단계와 저널

모든 단계는 고정 ACK 문자열 외에도 plan SHA/request/phase에 결합된 별도 offline 서명 영수증과 일회용 ApprovalId가 필요하다. 유효기간 2시간 이하, 남은 실행시간 1200초 이상이며 root 보호 approval-public.pem/json/sig를 검증한다. 기존 V45 승인서는 재사용하지 않는다. 두 저널 불일치, 순서 변경, 재사용은 거부한다.

| phase | 선행 상태 | 별도 ACK | 동작/완료 상태 |
|---|---|---|---|
| quiesce | legacy terminal | approve-maintenance-and-backup-window | exact V5/validated guard 보존 확인, host 계약/이미지 확인, systemd disable·restart=no·ALB drain·backend stop → quiesced |
| verify-writers | quiesced | approve-database-writer-fence-verification | 운영자 ACL 차단 후 실제 역할/ACL/세션/prepared/subscription/job 확인 → writers-verified |
| retire-guard | writers-verified | approve-snapshotted-v5-guard-retirement-no-image-rollback | fence 재검증, 이후 생성된 동일 RDS/account의 available manual V5 snapshot 확인, check-before, guard-changing 저널 후 고정 guard만 제거 → guard-retired |
| migrate | guard-retired | approve-backed-up-schema-change-no-image-rollback | 동일 snapshot/fence, V5 history/guard 부재/DDL권한 재검증, db-changing, V6–V18, history/권한/JPA 검증 → db-verified |
| verify-activation | db-verified | approve-external-app-connect-verification | 별도 운영자가 app CONNECT만 복구한 후 ACL·세션·V18·app JPA를 읽기 검증 → activation-verified |
| deploy | activation-verified | approve-verified-schema-image-activation | 활성화 ACL·JPA 재검증, image-changing, candidate 시작, Docker/ALB 연속 150초 health, Redis 동일성 → succeeded |

V45 guard는 사전 삭제하지 않는다. 정확한 public.experiences CHECK/validated 정의를 요구한다. retire 단계는 동일 트랜잭션 안에서 ACCESS EXCLUSIVE lock(5초 timeout), 정확한 V5 history/guard/COMING_SOON=0 확인, 고정 constraint DROP, history 불변 확인 후 commit한다. 정의/validated/소유권/lock 불일치면 rollback한다. 기존 Flyway SQL을 수정하거나 repair/baseline/clean으로 우회하지 않는다. guard-retired부터 V5 이미지 자동 복귀 금지이며 guard 제거만 성공했어도 저널을 수동으로 초기화하지 않는다.

Fence hash는 DB OID/owner와 정렬한 ACL이며 **app의 grant option 없는 직접 CONNECT 한 항목만** 제외한다. 이 예외는 verify-activation/deploy의 승인된 app 활성화에만 허용된다. 다른 ACL 변경/상속 CONNECT/새 특권 LOGIN/잔존 client는 거부한다. privileged 운영자가 향후 우회하지 못한다는 보장은 아니다. 유지보수 credential/외부 job 사용 통제를 승인하고 유지해야 한다.

## 새 SSM 경로 검토

문서는 PROPOSED 상태이며 설치하지 않았다. 기존 `/opt/hobbytree/scripts/deploy`와 분리된 `/opt/hobbytree/scripts/deploy-schema`에만 배치하는 실행안이다. render-transition-document.py가 두 helper의 정확한 SHA를 고정한다. 디렉터리 root:root0755, helper root:root0500, 비심볼릭링크, env-i/Python-I, ENV_VAR 보간/고정 parameter regex, Linux root, 공용 host lock을 요구한다. timeout2700초가 전진 및 containment 예산을 포함한다.

migration credential은 `/run/hobbytree/migration-secrets` UID10001:mode0500 및 파일0400으로만 공급한다. credential parent/CA/plan/journal은 root 보호다. Docker는 UID10001, read-only mount/rootfs, no caps,512MB, host network, PropertiesLauncher 고정 main을 사용한다. 공개 database-policy.tsv를 plan에서 생성하고 매번 bytes를 비교한다. DB URL은 실제 승인 RDS hostname, DB hobbytree, TLS verify-full로 고정한다. 일반 app entrypoint/Flyway startup은 사용하지 않는다.

SSM document IAM/SendCommand/instance profile ECR pull/RDS describe/ALB 권한, 승인 서명자 분리, CA/secret 공급은 아직 입증되지 않았다. AWS 변경 전 담당자가 별도로 독립 검토해야 한다. 이 문서는 신규 권한 부여를 승인하지 않는다.

## 실패/복구

부분 migration, guard-changing/db-changing/image-changing 결과 불명, history/hash/JPA 불일치면 maintenance를 유지한다. lock 안에서 stop/disable/restart=no/ALB 제외를 각각 시도하고 containment_failures를 남긴다. `external_operator_acl_recovery_required=true`를 기록한다. host containment가 다른 DB writer까지 닫았다고 주장하지 않는다. 특히 app CONNECT가 열렸다면 별도 권한 있는 운영자가 ACL 회수 및 세션 정리를 수행해야 하며, 성공 증거 전 복구 완료로 판정하지 않는다.

V5 복원은 새 snapshot에서 별도 복구 DB로 복원하고 정확한 V5 history/guard/권한/데이터를 검증하는 독립 승인 절차다. 부분 V18 DB에 이전 이미지 실행, Flyway 이력 repair/delete, SQL 덮어쓰기, 저널 terminal 강제 변경은 금지한다. V20 통합 실패가 해소되어도 V18 artifact/소유권/운영 승인 증거를 대신하지 않는다.

공식 근거: [PostgreSQL privileges](https://www.postgresql.org/docs/17/ddl-priv.html), [privilege inquiry functions](https://www.postgresql.org/docs/17/functions-info.html), [ALTER TABLE](https://www.postgresql.org/docs/17/sql-altertable.html). 실제 AWS 상태는 제공 결과와 추가 read-only 증거를 기준으로 판정한다.

추가 AWS 읽기 전용 점검 예시(이번 작업에서는 실행하지 않음):

```sh
aws rds describe-db-instances --region ap-northeast-2 --db-instance-identifier hobbytree-dev-private --query 'DBInstances[*].{Arn:DBInstanceArn,Status:DBInstanceStatus,Endpoint:Endpoint,EngineVersion:EngineVersion,MasterUsername:MasterUsername}'
aws rds describe-db-snapshots --region ap-northeast-2 --db-instance-identifier hobbytree-dev-private --snapshot-type manual --query 'DBSnapshots[*].{Arn:DBSnapshotArn,Status:Status,Created:SnapshotCreateTime,Instance:DBInstanceIdentifier}'
```

[DescribeDBInstances](https://docs.aws.amazon.com/cli/latest/reference/rds/describe-db-instances.html) 및 [DescribeDBSnapshots](https://docs.aws.amazon.com/cli/latest/reference/rds/describe-db-snapshots.html) 성공이 향후 snapshot 생성/복원 또는 SSM/ALB 변경 권한을 증명하지 않는다. 해당 IAM policy/permission boundary/SCP와 작업 주체는 별도 읽기 검토가 필요하다.
