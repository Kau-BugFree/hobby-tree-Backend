# V18 신규 SSM·호스트 도구 설치 운영 절차

이 문서는 **설치 준비 산출물**이다. 작성·격리 테스트 중 실제 AWS/EC2 설치, CreateDocument, SendCommand, ECR push, RDS SQL, 서비스 변경은 실행하지 않는다. 아래 변경 명령은 각각 명시적인 운영 승인을 받은 뒤 지정된 운영자만 실행한다. PR #138은 Draft를 유지한다. develop V20 통합 수정과 자동 CI/CD 고도화는 범위 밖이다.

## 역할과 승인 경계

| 역할 | 보관/수행 | 분리 기준 |
|---|---|---|
| 검토자 | 최종 Git SHA, archive/manifest/hash, V18 테스트/JAR/OCI evidence 독립 대조 | 검토 영수증을 설치자·실행자에게 전달 |
| 오프라인 승인자 | 암호화 개인키, 승인 내용·DB 위험 검토, phase별 서명 | 개인키를 EC2/GitHub/패키지/로그에 반입하지 않음 |
| 설치 관리자 | 검토된 archive와 공개키만 root 경로에 설치, 신규 SSM 문서 등록 | 배포 실행 권한과 분리. 기존 파일 덮어쓰기·키 교체 금지 |
| 배포 실행자 | 승인된 plan/receipt 반입, 고정 문서 version/hash로 phase 실행 | 개인키·IAM 수정·문서 수정 권한 불필요 |
| DB CONNECT 운영자 | DB ACL 차단/활성화/복구 | 비관리자 migration 역할과 별도 |
| snapshot/복구 운영자 | 새 V5 snapshot·복구 승인 | describe 성공은 생성·복원 권한 증거가 아님 |

현재 hobbytree-readonly에는 쓰기 권한을 추가하지 않는다. 설치 담당자의 ssm:CreateDocument와 실행자의 신규 문서+단일 EC2에 한정된 ssm:SendCommand/결과 조회를 별도로 검토한다. 기존 GitHub 역할의 허용 목록은 v2/V45/Preflight뿐이며 신규 문서 허용은 별도 승인 대상이다. 호스트 설치는 검토된 root 작업 수단으로 수행한다. 이번 작업은 역할을 전환하지 않는다.

## 파일 배치와 권한

| 대상 | uid:gid | mode | 처리 |
|---|---|---|---|
| /opt/hobbytree/scripts/deploy-schema/ | 0:0 | 0755 | 신규 전용 디렉터리 |
| ec2-release.py / schema-transition.py / schema-install.py | 0:0 | 0500 | Git blob과 같은 bytes, 기존 파일이 다르면 중단 |
| /etc/hobbytree/transitions/ 및 approvals/ | 0:0 | 0700 | 신규 설정/승인서 경로 |
| installation-manifest.json / approval-public.pem | 0:0 | 0400 | 최초 설치, 같은 bytes만 재실행 허용 |
| `<request>.json`, approvals/`<id>.json`, `<id>.sig` | 0:0 | 0400 | 각각 stage-plan/stage-approval로 신규 생성 |
| /run/hobbytree/migration-secrets/ | 10001:10001 | 0500 | migration 전용 경로 |
| DB_USERNAME / DB_PASSWORD | 10001:10001 | 0400 | credential 전용 승인 명령. 값/hash 출력 없음 |

기존 /opt, /opt/hobbytree, /opt/hobbytree/scripts는 root:root0755이어야 한다. /etc/hobbytree, /run/hobbytree, /var/lib/hobbytree/deployments는 root 소유이고 group/other 쓰기가 금지되어야 한다. 기존 공용 lock과 active.json이 반드시 있어야 한다. 설치·credential·plan 반입은 legacy terminal 상태에서만 허용한다. 승인서 반입은 해당 phase의 정확한 비종결 상태에서도 동일 lock 안에서 허용한다. 기존 저널을 초기화하거나 terminal로 강제 변경하지 않는다.

설치 도구는 AWS/Docker/systemd/DB를 호출하지 않는다. 기존 Backend/Redis/Compose/unit/SSM v2/V45 파일을 열어 쓰지 않는다. symlink/hardlink, 다른 owner/mode/hash, 미승인 파일, 사용 중 lock, 비종결 legacy 저널이면 중단한다. preflight는 파일을 생성하지 않는다. install은 O_EXCL/fsync로 신규 파일만 생성하며 덮어쓰지 않는다. 중간 실패 시 신규 부분 파일도 자동 삭제하지 않으며 **동일 패키지**로만 검증/재시도한다. 잘못된 기존 파일은 별도 원인 검토와 수정 승인 대상이다.

## 1. 빌드 담당자 — 로컬 패키지 봉인

최종 커밋이 clean이고 전체 검증이 완료된 checkout에서 실행한다. 빌더는 dirty/다른 HEAD를 거부하며 Git blob만 읽는다. SQL/JAR/image/키/credential은 패키지에 포함하지 않는다.

```sh
REV=$(git rev-parse HEAD)
python3 scripts/ci/build-schema-install-bundle.py --revision "$REV" --output /secure/reviewed-bundle
```

산출물은 archive, 안전한 해제 도구 extract-schema-install.py, install-bundle-receipt.json이다. archive는 uid/gid/mtime/정렬을 고정해 재현 가능하다. manifest의 파일 hash·경로·mode와 SSM JSON이 동일 source bytes 및 6 phase 정의와 일치해야 한다. archive SHA256, extractor SHA256, manifest SHA256, source SHA를 별도 신뢰 경로로 관리자에게 전달한다. archive 옆 checksum 파일만으로 승인을 대체하지 않는다.

## 2. 승인자 — 오프라인 공개키 준비

다음 명령은 이번 작업에서 운영 키로 실행하지 않는다. Git checkout 밖의 승인자 전용 Linux 장치에서 TTY로 암호를 입력한다. 암호를 인수·환경변수·파일·로그에 넣지 않는다. 개인키와 암호의 백업/복구는 조직의 비밀 관리 절차를 따른다.

```sh
umask 077
mkdir -m 700 /secure/hobbytree-approval
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -aes-256-cbc \
  -out /secure/hobbytree-approval/private.pem
openssl pkey -in /secure/hobbytree-approval/private.pem -pubout \
  -out /secure/hobbytree-approval/approval-public.pem
sha256sum /secure/hobbytree-approval/approval-public.pem
```

공개키 bytes SHA256을 검토자/관리자/실행자가 독립 대조한다. 공개키만 EC2와 검증 장치에 전달한다. schema-phase-approval.py sign은 암호화된 mode0600 본인 소유 개인키와 Git 외부 경로를 요구한다. 키 교체는 도구가 자동 수행하지 않는다. 기존 V45 키/승인서를 재사용하지 않는다. root가 임의 키/도구를 바꾸는 행위를 암호학적으로 차단하는 체계는 아니므로 root와 서명자의 책임을 분리한다.

## 3. 설치 관리자 — archive 검증·해제·preflight·설치

**별도 설치 승인 후** 검토 영수증의 실제 값으로 변수를 채운다. placeholder가 남아 있으면 진행하지 않는다. 기존 승인된 파일 전달 수단으로 root 전용 staging에 산출물과 공개키를 반입하고 /root/hobbytree-schema-install 부모를 root:root0700으로 준비한다. 아래는 운영자가 실행할 설치 계획이며 이번 작업에서 실행하지 않는다.

```sh
set -eu
set +x
umask 077
REV='<최종 검증 SHA>'
ARCHIVE_SHA='<독립 대조한 archive SHA256>'
EXTRACTOR_SHA='<독립 대조한 extractor SHA256>'
MANIFEST_SHA='<독립 대조한 manifest SHA256>'
KEY_SHA='<승인한 공개키 bytes SHA256>'
STAGE="/root/hobbytree-schema-install/$REV"
ARCHIVE="/root/hobbytree-schema-install/hobbytree-schema-install-$REV.tar.gz"
EXTRACTOR='/root/hobbytree-schema-install/extract-schema-install.py'
KEY='/root/hobbytree-schema-install/approval-public.pem'
printf '%s  %s\n' "$EXTRACTOR_SHA" "$EXTRACTOR" | sha256sum --check --status
mkdir -m 700 "$STAGE"
python3 -I "$EXTRACTOR" --archive "$ARCHIVE" --archive-sha256 "$ARCHIVE_SHA" --destination "$STAGE"
INSTALLER="$STAGE/tools/schema-install.py"
python3 -I "$INSTALLER" preflight --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" \
  --public-key "$KEY" --public-key-sha256 "$KEY_SHA"
# 다음 명령부터 신규 파일 생성. INSTALL 승인 후에만 실행한다.
python3 -I "$INSTALLER" install --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" \
  --public-key "$KEY" --public-key-sha256 "$KEY_SHA" \
  --ack install-isolated-schema-tools-without-service-or-aws-changes
```

해제 도구는 승인된 hash, 정확한 8개 일반 파일, 고정 경로/owner/mode/크기를 모두 확인한 뒤 빈 root staging에만 기록한다. Python 최신 tar filter 기능에 의존하지 않는다. 이미 해제한 동일 staging에는 해제를 반복하지 않고 installer preflight부터 재개한다. 해제 자체가 부분 실패했다면 staging을 보존하고 검토한다. 자동 삭제/덮어쓰기로 복구하지 않는다.

## 4. migration credential 별도 반입

별도 승인 후 기존 secret 공급 수단으로 **migration 역할**의 DB_USERNAME/DB_PASSWORD 파일을 root0600 staging에 준비한다. app credential을 migration으로 복제하지 않는다. 값은 표시하지 않고 username=hobbytree_migration만 내부 검증한다. 기존 UID10001 mode0400 파일도 입력으로 사용할 수 있다. 다른 기존 migration 파일은 덮어쓰지 않는다. Secret JSON field를 추정하지 않고 기존 공급 처리의 출력 파일을 사용한다.

```sh
python3 -I "$INSTALLER" credentials --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" \
  --public-key "$KEY" --public-key-sha256 "$KEY_SHA" \
  --username-file /root/private-migration/DB_USERNAME --password-file /root/private-migration/DB_PASSWORD \
  --ack install-migration-credentials-only
python3 -I /opt/hobbytree/scripts/deploy-schema/schema-install.py verify \
  --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" --public-key "$KEY" --public-key-sha256 "$KEY_SHA"
```

/run은 재부팅 시 사라진다. 자동 공급이나 systemd 변경은 이 설치에 포함하지 않는다. 각 transition 전에 verify와 승인된 공급 절차를 수행한다. source staging Secret 정리는 기존 비밀 운영 절차로 수행하며 이 도구는 관련 없는 기존 파일을 삭제하지 않는다.

## 5. 신규 SSM 문서 생성안

**별도 AWS 변경 승인 후** 지정된 문서 설치 담당자만 실행한다. readonly profile과 설치자를 구분한다. 기존 v2/V45의 version/default/권한은 변경하지 않는다.

```sh
aws ssm describe-document --name HobbyTree-Dev-SchemaTransition \
  --profile hobbytree-readonly --region ap-northeast-2
# InvalidDocument=미존재 확인. AccessDenied라면 미존재로 판정하지 않고 중단한다.
# 다음 CreateDocument는 승인된 설치자만 실행한다.
aws ssm create-document --name HobbyTree-Dev-SchemaTransition --document-type Command \
  --document-format JSON --content "file://$STAGE/document/HobbyTree-Dev-SchemaTransition.json" \
  --profile '<승인된 설치자profile>' --region ap-northeast-2
aws ssm describe-document --name HobbyTree-Dev-SchemaTransition \
  --profile hobbytree-readonly --region ap-northeast-2
```

Active/version1/SHA256을 기록하고 get-document JSON canonical이 승인 artifact와 같은지 확인한다. version1이 이미 있으면 자동 update/삭제하지 않고 내용/hash를 재검토한다. ENV_VAR는 Agent>=3.3.2746.0이 필수다. 관측 Agent3.3.5390.0은 조건을 만족하지만 직전에 Online/version을 재확인한다. raw 문자열 대입 fallback은 추가하지 않는다.

## 6. plan·phase 서명·실행

실제 release는 별도 승인한다. 우선 V18 후보의 ECR manifest digest·OCI revision·JAR/hash/SQL/history/test evidence를 최종 SHA에 맞춘다. 로컬 image ID를 ECR digest로 쓰지 않는다. prepare-schema-transition.py format3 plan은 writer 차단 **정책**만 기록하며 2시간 이내 window의 새 request를 사용한다. DB owner=postgres, migration=hobbytree_migration, app=hobbytree_app, privileged LOGIN 목록은 실제 read-only 결과를 사용한다. 전체 plan 입력은 `prepare-schema-transition.py --help`와 [전환 실행안](v5-latest-transition-runbook.md)을 따른다.

```sh
python3 -I "$INSTALLER" stage-plan --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" \
  --public-key "$KEY" --public-key-sha256 "$KEY_SHA" --input /root/reviewed/REQUEST.json \
  --ack install-reviewed-transition-plan
# 다음 명령은 승인자의 오프라인 장치에서 각 phase 직전에 실행한다.
python3 tools/schema-phase-approval.py draft --plan /secure/reviewed/REQUEST.json \
  --phase quiesce --output-directory /secure/receipts
python3 tools/schema-phase-approval.py sign --plan /secure/reviewed/REQUEST.json --phase quiesce \
  --body /secure/receipts/ID.json --signature /secure/receipts/ID.sig \
  --private-key /secure/hobbytree-approval/private.pem --public-key /secure/hobbytree-approval/approval-public.pem \
  --public-key-sha256 '<독립 대조한 공개키SHA256>'
python3 tools/schema-phase-approval.py verify --plan /secure/reviewed/REQUEST.json --phase quiesce \
  --body /secure/receipts/ID.json --signature /secure/receipts/ID.sig \
  --public-key /secure/hobbytree-approval/approval-public.pem --public-key-sha256 '<동일SHA256>'
# 공개 receipt/sig만 host root staging에 반입한다.
python3 -I "$INSTALLER" stage-approval --bundle "$STAGE" --manifest-sha256 "$MANIFEST_SHA" \
  --public-key "$KEY" --public-key-sha256 "$KEY_SHA" --input /root/reviewed/ID.json \
  --signature /root/reviewed/ID.sig --ack install-offline-signed-phase-approval
```

서명 대상은 format1, approval_id32hex, request, phase, plan_sha256, issued_at/expires_at Unix초다. 수명<=7200초, 잔여>=1200초, plan 만료 이하다. 실행 host는 공용 lock 안에서 서명/context/만료 확인 후 STATE/schema-approval-ID.used를 O_EXCL/fsync로 영구 소비한다. 실패해도 ID를 재사용하지 않는다. 오프라인 verify만으로 host에서 미사용임을 증명하지는 않는다. 서명 도중 실패하면 생성된 불완전 sig와 draft를 보존하고 새 ID로 다시 승인한다.

| phase | 선행 상태 | 고정 Approval 문자열 |
|---|---|---|
| quiesce | legacy terminal | approve-maintenance-and-backup-window |
| verify-writers | quiesced | approve-database-writer-fence-verification |
| retire-guard | writers-verified + 신규 V5 snapshot available | approve-snapshotted-v5-guard-retirement-no-image-rollback |
| migrate | guard-retired | approve-backed-up-schema-change-no-image-rollback |
| verify-activation | db-verified + 운영자가 app CONNECT 허용 | approve-external-app-connect-verification |
| deploy | activation-verified | approve-verified-schema-image-activation |

실행자는 아래 형식의 parameters.json을 준비하고, 승인된 문서의 **숫자 version과 AWS document hash**를 pin한다. 각 phase마다 별도 서명/승인이 필요하며 여섯 서명을 미리 한꺼번에 발급하지 않는다. 다음 SendCommand도 이번 작업에서 실행하지 않는다.

```json
{"RequestId":["REQUEST"],"PlanSha256":["64hex"],"Phase":["quiesce"],"Approval":["approve-maintenance-and-backup-window"],"ApprovalId":["32hex"]}
```

```sh
aws ssm send-command --document-name HobbyTree-Dev-SchemaTransition --document-version 1 \
  --document-hash '<AWS describe에서 승인한hash>' --document-hash-type Sha256 \
  --instance-ids i-0d71fcf646b7210f2 --parameters file:///secure/reviewed/parameters.json \
  --max-concurrency 1 --max-errors 0 --profile '<승인된 실행자profile>' --region ap-northeast-2
```

CommandId로 결과를 읽기 조회하고 host의 두 저널과 대조한 뒤 다음 서명을 받는다. 설치 승인에는 quiesce·DB·image 변경 승인이 포함되지 않는다.

## 중단 조건 및 최종 점검표

- 서명/hash/owner/mode/agent/역할/대상/version 불일치, 사용한 approval, 잔여 시간 부족, lock 점유, 기존 파일 drift는 즉시 중단한다.
- 설치 담당자/공개키 fingerprint/최종 package SHA/실행자 권한/credential 공급 방법/공용 저널 terminal 상태를 독립 검토한다.
- install/verify는 Backend/Redis/ALB/systemd를 변경하지 않는다. 실제 전환 전후 writer fence·DB history·JAR·host contract를 재검증한다.
- guard는 새 V5 snapshot 및 retire-guard 서명 승인 이후에만 제거한다. guard-changing/db-changing/image-changing 실패·결과 불명은 maintenance/recovery-required를 유지한다.
- 이전 image 자동 시작, Flyway repair/history 삭제, 기존 SQL 변경, 저널 초기화는 금지한다. app CONNECT 복구/회수는 별도 DB 운영자 책임이다.
- 부분 설치는 동일 archive로 검증한 뒤 재시도한다. 다른 버전·키로 덮어쓰기나 자동 정리는 하지 않는다. 재부팅으로 /run이 사라지면 credential 공급 승인을 재확인한다.
- 사용자가 제공한 실제 DB/IAM 시뮬레이션/host 무결성 증거는 수령한 증거로 구분한다. 이번 로컬 테스트가 실제 RDS·host를 재검증한 것으로 표시하지 않는다.

준비 판정은 전체 로컬 검증·최종 SHA/package 일치 완료와 실제 공개키·설치자·실행 권한·credential 공급·직전 preflight 승인을 조건으로 **CONDITIONAL GO(설치 준비)**다. 실제 AWS 설치와 V18 release는 각각 별도 승인 전 NO-GO다.

공식 근거: [ENV_VAR/Agent 요건](https://docs.aws.amazon.com/systems-manager/latest/userguide/parameter-troubleshooting.html), [CreateDocument](https://docs.aws.amazon.com/systems-manager/latest/APIReference/API_CreateDocument.html), [SendCommand version/hash](https://docs.aws.amazon.com/systems-manager/latest/APIReference/API_SendCommand.html).
