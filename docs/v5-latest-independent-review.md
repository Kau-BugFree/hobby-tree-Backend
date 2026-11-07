# V5 → V18 전환 재검토와 운영 독립 검토 항목

**실제 AWS 적용 NO-GO / Draft 검토용.** AWS 변경·ECR push·SSM 실행/설치·RDS migration·EC2 재시작은 수행하지 않았다.
이 문서는 이전 `9189774` 후보 보고서의 실행 승인 근거를 대체한다. 최종 검증 SHA/이미지 ID/테스트 수는 Draft PR 본문과 동일 SHA의 검증 영수증으로 확인한다.
최신 develop `164e3110ab094baf834fe5a06cfd99db0484fef8`을 정상 merge했고 SQL은 기존 V1–V18 그대로다.

## 발견한 문제와 변경

1. 이전 `--writers-fenced`는 실제 제어 없이 true를 plan에 기록했다. 옵션과 boolean 필드를 제거했다.
   format2 plan은 차단 **정책**만 담으며, 구 format1 또는 `writers_fenced` 필드가 있으면 거부한다.
2. 이전에는 backend 정지 시각만으로 backup/migrate를 허용했다.
   이제 **plan → quiesce → 승인된 DB CONNECT 차단 → verify-writers → 새 snapshot → migrate → deploy** 순서다.
   실제 DB 증거가 있는 `writers-verified` 상태와 fence 시각/hash 없이는 migrate에 진입하지 못한다.
3. 제한된 DB owner에게 다른 계정의 pg_stat_activity.backend_type이 NULL로 보이는 경우를 실제 테스트에서 발견했다.
   NULL을 제외하면 이미 연결된 idle client를 놓치므로 이제 불명 세션도 차단한다. REVOKE 이후에도 기존 연결은 따로 확인한다.
4. 고정 승인 문구는 실제 승인서가 아니었다. 별도 offline 공개키 검증으로 phase/request/plan hash/만료를 묶고
   일회용 승인 ID를 lock 안에서 fsync해 소비한다. 문구만으로 진행할 수 없으며 다른 phase에 서명을 재사용할 수 없다.
5. 이전 실패 복구는 lock을 해제한 뒤 실행했고, 첫 조치가 실패하면 뒤의 격리를 건너뛸 수 있었다.
   복구를 lock 안으로 옮기고 stop/disable/restart 차단/target 제외/앱 CONNECT 회수를 각각 시도한다.
   실패 목록을 기록하고 nonterminal journal을 유지한다. SIGTERM도 같은 경로를 거친다.
6. 이전 단계 간 검증은 SQL fingerprint 위주였다. 실제 전체 JAR SHA256, image ID/digest/revision,
   원본 settings/history 보호 파일과 두 저널 일치도 확인한다. SQL이 같아도 application JAR가 다르면 거부한다.
7. INSERT 외 Flyway history 쓰기 권한과 RDS/서버 관리 role membership도 제한 계정 검사에 포함했다.
   DB owner와 앱은 비관리자 역할이어야 하며 앱에 owner를 부여하지 않는다.
8. plan/승인서에 phase 실행 시간20분 이상이 남아 있어야 하고 실행 deadline은 plan 만료를 넘지 않는다.
   SSM 문서의 timeout도 forward와 containment를 합한 최악 시간보다 길게 조정했다.

## 실제 fence가 증명하는 범위

PUBLIC CONNECT, owner 이외 일반 LOGIN 역할의 effective CONNECT(상속 포함), 앱 CONNECT,
기존/불명 세션, prepared transaction, enabled subscription 및 알려진 scheduler 확장을 실제 DB에서 검사한다.
조건을 만족한 뒤 DB OID/owner/ACL hash와 검증 시각을 기록하고 migrate 앞뒤와 deploy 전에 다시 확인한다.
`quiesced`는 정지 증거만 있으며 writer 차단 증거가 아니다. backend 정지 후라도 fence 검증 전 snapshot은 허용하지 않는다.

DB owner와 superuser가 향후 의도적으로 우회하는 것까지 ACL로 차단할 수는 없다. 특권 credential·외부 job·관리 작업 통제는
별도의 운영 검토/승인 gate로 남긴다. RDS 역할 inventory가 코드의 보수적 조건과 다르면 임의 예외 추가 대신 중단한다.
읽기 전용 계정이나 관리 작업도 CONNECT 차단의 영향을 받을 수 있으므로 원래 ACL/grant chain을 먼저 검토·보관해야 한다.
([PostgreSQL 권한](https://www.postgresql.org/docs/17/ddl-priv.html), [RDS 관리 역할](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.Roles.rds_superuser.html))

fence를 유지하면서 owner 인증 연결에 SET ROLE app을 적용해 JPA/업무 권한을 검사한다.
Hibernate 연결의 current_user가 앱인지 검사한다. 실제 앱 로그인/TLS/role login 기본 설정 검증은 배포 Health/API smoke에 남아 있다.
([SET ROLE 의미와 제한](https://www.postgresql.org/docs/17/sql-set-role.html))

## 독립 검토할 실행 코드

| 파일 | 운영 담당자가 볼 조건 |
|---|---|
| [schema-transition.py](../scripts/deploy/schema-transition.py) | plan format2, root 파일·hash, 동일 host lock, 두 journal, 네 phase/서명·소비, snapshot 시각, containment 실패 처리 |
| [SchemaMigrationCli.java](../src/main/java/com/hobbytree/hobbytree/deployment/SchemaMigrationCli.java) | 정확한 DB/계정/TLS, 실제 ACL·상속·세션 NULL·prepared/subscription 검사, CRC/history, SET ROLE/JPA, 앱 CONNECT 재개·회수 |
| [HobbyTree-Dev-SchemaTransition.proposed.json](../deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json) | 신규 이름, 단일 고정 단계, ENV_VAR·패턴, script SHA·소유권·symlink, ApprovalId 인수, 기존 문서 변경 없음 |
| [prepare-schema-transition.py](../scripts/ci/prepare-schema-transition.py) | Git/JAR/history 결합, 새 policy-only plan, migration_user·jar hash, 입력 검증, writer 차단을 선언할 옵션 없음 |
| [render-transition-document.py](../scripts/ci/render-transition-document.py) | 검토한 Python 파일의 실제 SHA가 JSON에 들어가는지, 네 phase/서명 입력 대응 |
| [FlywayMigrationTests.java](../src/test/java/com/hobbytree/hobbytree/migration/FlywayMigrationTests.java) | 실제 PG17·V5→V18, 이미 연결된 client, CONNECT 상속, ACL drift, prepared transaction, 관리 역할/이력 권한 거부 |
| [test_schema_transition.py](../scripts/tests/test_schema_transition.py) | 순서·snapshot·해시, 실제 OpenSSL 서명 변조/만료/context, 일회 소비, lock 안 복구·각 조치 독립 실행 |
| [실행안](v5-latest-transition-runbook.md) | 실제 ACL 차단의 별도 승인, signer/key 전달·보관, 복구 담당자·window·timeout, 실제 명령 |

기존 `ec2-release.py`, `v45_gate.py`, SSM v2/V45 JSON, V1–V18 SQL은 변경하지 않는다.
공통 helper를 side-by-side 신규 경로에서 재사용할 뿐 기존 동일 SQL 조건/서명/실패 복구를 완화하지 않는다.

## 로컬 재현과 SHA 일관성

```powershell
git rev-parse HEAD
./scripts/ci/rehearse-upgrade.ps1 -OutputDirectory '<보호된 로컬 검증 폴더>'
```

runner의 PostgreSQL은 max_prepared_transactions=10으로 설정해 기존 세션 없는 prepared transaction까지 실제 생성/거부를 검사한다.
이는 **로컬/CI fixture 설정**이며 RDS parameter 변경을 뜻하지 않는다. 모든 테스트는 스킵0을 확인한다.
서명 테스트의 private key는 네트워크 없는 테스트 컨테이너의 임시 디렉터리에서만 생성/제거하며 실제 승인 키가 아니다.

최종 소스를 커밋한 후 그 SHA를 OCI revision으로 빌드한다. unstarted/network-none 컨테이너에서 JAR를 추출하고
Git SQL 원문, V5 baseline prefix, 실제 격리 Flyway history/CRC, 전체 JAR hash를 검증 영수증에 묶는다.
remote push 후 branch HEAD와 Draft PR head가 그 SHA인지 확인한다. ECR manifest digest는 아직 없으므로 null로 기록한다.

## 실제 AWS 적용 차단 요소

- 신규 SSM/side-by-side host tool·공개키·서명 승인서 설치와 운영 역할/권한 검토가 끝나지 않았다.
- 실 RDS의 전용 DB owner/SET app 권한, CONNECT ACL/grant chain, 특권 계정·외부 writer 통제, 정확한 V5 이력/guard를 실행 직전에 확인해야 한다.
- quiesce와 별도 승인한 실제 DB ACL 차단·live verify-writers 이후 **새 수동 snapshot available** 증거가 필요하다.
- 검증된 이미지의 ECR 게시/digest, 각 phase 서명 승인 및 운영 systemd/ALB/SSM end-to-end 검증은 수행하지 않았다.
- Web origin/configtree, Kakao REST/S3 기능 설정, 담당자 테스트 계정/실 콘텐츠와 실제 provider/API smoke는 별도 gate다.
- 최초 V18 준비가 우선이다. ECR 후보 자동 게시/CD 통합은 후속이며 이 PR에서 활성화하지 않는다. develop에 merge하지 않는다.
