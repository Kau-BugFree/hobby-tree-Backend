# 승인형 V4·V5 배포 전환

기준 develop: 3dec0c8497cf0f1d9ef2ad38b84d75d809fce7e8 / Issue #59.
AWS 설치·배포는 실행하지 않는다. 아래 도입 조건이 충족되기 전 운영은 NO-GO다.

## 기존 방식과 차이

Workflow의 v45_approval이 비어 있으면 기존 SSM version 2, migration-set equality/no-op을 그대로 사용한다.
ID를 지정하면 HobbyTree-Dev-DeployBackendV45 version 1을 호출한다. 실제 AWS의 문서/버전 존재는 미확인이다.
새 문서는 별도 /opt/hobbytree/scripts/deploy-v45 경로의 두 스크립트를 SHA256으로 고정한다.
기존 /opt/hobbytree/scripts/deploy 설치본과 SSM v2는 교체하지 않는다.
기존 문서 JSON의 갱신 hash는 향후 일반 경로 업데이트 제안이며 AWS v2를 덮어쓰라는 뜻이 아니다.
새 경로는 V3→V5 전용이다. 동일 이미지/no-op에는 기존 빈 approval 경로를 사용한다.

Digest CAS, OCI Git SHA, amd64/non-root, Flyway=false/validate, Redis ID/전체 mount, ALB 150초 관찰,
journal, 배포 lock, 설정 snapshot과 복구 보호를 유지한다. RDS migration이나 ECR push는 포함하지 않는다.

## 서명 승인

오프라인 RSA 서명자가 실제 CI 결과·이미지 pair·DB migration을 확인한다. private key는 EC2/GitHub에 두지 않는다.
EC2는 root 소유 공개키와 openssl dgst -sha256 -verify로 원본 JSON bytes의 서명을 검증한다.
/etc/hobbytree/v45/approvals/ID.json, ID.sig, ID.report.json을 root:root 600으로 설치한다.
모든 부모 경로도 root 소유·다른 사용자 쓰기 금지이며 symlink는 거부한다.
승인 ID는 소문자 hex 32자리다. 다음은 구조 예시이며 유효한 승인 값이 아니다.

```json
{
  "format": 1,
  "approval_id": "32 lowercase hex",
  "previous": "494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend@sha256:OLD",
  "candidate": "494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend@sha256:NEW",
  "previous_revision": "be9375a8dde209c300e592c41f9c4addd8ebc793",
  "revision": "40 lowercase hex",
  "before_schema": "65c88b864b804ed8ecc76e18c13a4d4d51c509cab0bbbe688a4c05a884f93903",
  "after_schema": "84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026",
  "database": {"host": "EXACT.rds.amazonaws.com", "port": 5432, "dbname": "hobbytree", "identity": "operator-recorded-uuid"},
  "issued_at": 0,
  "expires_at": 0,
  "validation_report_sha256": "sha256 hex of ID.report.json bytes"
}
```

유효기간은 발급부터 최대 2시간이며 준비 시 forward 최대 시간(1200초) 이상 남아 있어야 한다. EC2 시계 동기화가 전제다. 이전 소스는 검증한 be9375a8로 제한한다.
V1~V3 및 V1~V5의 전체 SQL 집합 SHA256이 고정값과 일치해야 하므로 SQL 수정/삭제/V6 추가는 거부된다.
ID.report.json은 같은 revision/candidate와 migration_tests=9, failures/errors/skipped=0,
docker_validation="success"를 포함한 JSON object다. 보고서 hash도 서명에 결합된다.
서명자는 CI 원본·후보 Digest와 이 보고서를 대조해야 한다. hash 문자열만 있는 누락 보고서는 거부한다.
오프라인 서명 명령: `openssl dgst -sha256 -sign OFFLINE_PRIVATE_KEY -out ID.sig ID.json`.
EC2에서 승인을 발급하는 기능은 없다. 승인 소비는 STATE/v45-ID.used를 O_EXCL+fsync로 기록한다.
준비 후 실패해도 재사용하지 않는다. 원인/journal 확인 후 새 승인이 필요하다.

## 실제 DB와 롤백 보호

root 전용 /etc/hobbytree/v45/config.json 구조:

```json
{
  "public_key": "/etc/hobbytree/v45/approver-public.pem",
  "database": {"host": "EXACT.rds.amazonaws.com", "port": 5432, "dbname": "hobbytree", "identity": "operator-recorded-uuid"},
  "reader": "hobbytree_release_reader",
  "password_file": "/run/hobbytree/v45/reader-password",
  "ca_file": "/etc/hobbytree/certs/rds-ca.pem"
}
```

비밀번호 파일은 root:root 600이며 공개키·CA·config와 부모도 root 관리한다. Secret 조회 기능은 추가하지 않았다.
전용 SELECT reader만 사용한다. superuser/CREATEROLE/CREATEDB/replication/BYPASSRLS, experiences 소유권 상속,
public CREATE를 거부한다. reader에 아래 세 테이블의 SELECT 외 DML/DDL 권한을 부여하지 않는다.
실제 endpoint+verify-full+DB 이름+deployment_control.identity(identity text)의 단일 UUID 행을 대조한다.
UUID는 clone에도 복제될 수 있어 endpoint 확인을 함께 수행한다. RDS ARN을 SQL만으로 확인했다고 주장하지 않는다.
public.flyway_schema_history 전체가 정확히 V1~V5의 script/CRC checksum/success와 일치해야 한다.

V4/V5를 별도 migration 계정으로 적용하고 strict validate한 뒤, 별도로 승인한 운영 안전 DDL로 다음을 설치한다.

```sql
ALTER TABLE public.experiences
  ADD CONSTRAINT hobbytree_v45_rollback_guard CHECK (listing_status <> 'COMING_SOON');
```

NOT VALID는 허용하지 않는다. 기존 COMING_SOON 행이 있으면 설치부터 실패하며 전환은 NO-GO다.
이 CHECK는 앱·배치·직접 SQL의 새 상태 쓰기를 DB에서 거부한다. 운영자 약속만으로 대체하지 않는다.
모든 DDL을 Flyway로 관리해야 한다면 운영 control schema/제약 도입도 별도 location/history의 승인된 운영
migration으로 관리한다. 기존 public history에 V6를 추가하거나 V4/V5를 수정하지 않는다.
이 bootstrap 관리 방식이 팀 정책과 합의되지 않으면 운영은 NO-GO다. PR에서 해당 DDL은 실행하지 않는다.

배포 동안 같은 연결과 transaction에서 experiences/history/identity SELECT의 AccessShare lock을 유지한다.
guard 제거 등 AccessExclusive DDL을 차단하고 정상 DML은 허용한다. 장기 DDL 대기는 점검 창에서 관리한다.
기동 전, Health/ALB 전후, rollback 전에 실제 이력·validated guard 정의·COMING_SOON 0건을 재확인한다.
연결 상실·불일치 시 이전 이미지를 강행 기동하지 않고 rollback-failed journal/exit20으로 중단한다.
이때 현재 backend 상태를 확정할 수 없으므로 추가 배포를 막고 journal/SSM/실제 container·ALB를 확인한다.
이미지 롤백은 DB 스키마·데이터를 복구하지 않는다. guard는 배포 성공 후에도 남는다.
구버전 복구 기간 종료 및 모든 reader 대응을 별도 승인한 뒤에만 guard를 해제한다.
관리자/root/DB owner의 악의적 DDL과 서명자의 오승인은 이 코드의 신뢰 경계 밖이다.

## 도입 순서와 SSM/IAM

1. Draft PR의 CI·CodeRabbit·서명 및 코드 보안 리뷰를 완료한다.
2. backup/복원 가능성, 단일 EC2 중단 시간, DB migration/control DDL/reader 설치를 별도로 승인한다.
3. 전용 runner로 V4/V5 적용→strict validate. 앱 content_reports SELECT/INSERT를 검증한다.
4. UUID·validated guard·SELECT reader를 도입하고 실제 DB 증거를 확보한다.
5. 새 root:root 700 deploy-v45 디렉터리에 LF의 ec2-release.py와 v45_gate.py를 root:root 500으로 설치한다.
   /opt/hobbytree/v45-venv는 root 소유·다른 사용자 쓰기 금지로 만들고 deploy/v45-requirements.txt 의존성을 설치한다.
   venv 전체 파일과 배포 wheel/lock을 별도 보안 검토한다. 새 SSM의 두 파일 hash와 호스트를 대조한다.
   기존 v2 설치 경로는 변경하지 않는다.
6. 새 SSM 문서의 실제 고정 version을 확인한다. GitHub SendCommand의 허용 Resource에
   arn:aws:ssm:ap-northeast-2:494915081306:document/HobbyTree-Dev-DeployBackendV45만 추가하도록 리뷰한다.
   기존 단일 instance·서울 리전 제한을 유지한다. document 쓰기, Secrets/KMS 서명, migration 권한은 주지 않는다.
   EC2 role의 AWS 추가 권한은 필요 없다. DB reader 파일 공급은 독립된 root 관리 절차로 수행한다.
   IAM JSON은 이번 PR에서 변경하지 않았다. SDK 소스 정책 생성은
   `uvx iam-policy-autopilot@latest generate-policies --help` 확인 후 지원 SDK 기준으로 수행할 수 있다.
   현재 코드는 AWS CLI 호출이므로 SDK 자동 분석만으로 정책 완전성을 보장하지 않는다.
7. 실제 CI report·Digest pair·DB 증거를 오프라인 서명하고 root가 승인 파일 3개를 설치한다.
8. 별도 운영 승인 후에만 workflow_dispatch에서 ID·Digest pair·새 SHA를 입력한다. 이 PR 작업에서는 실행하지 않는다.

## 검증과 한계

정확한 이미지 pair/SQL, 서명·유효기간·보고서·일회 사용, DB 식별/이력/guard, reader/TLS, 기존 Redis/ALB/host
조건이 모두 충족돼야 GO다. 기존 COMING_SOON, 미지원 구버전, V6, DB 연결·설정 누락은 NO-GO다.
로컬 격리 PG17에서 쓰기 차단·guard 제거 잠금·history 불일치·guard 해제 후 비호환을 검증한다.
CI는 실 OpenSSL 서명/변조와 기존 no-op·Redis·롤백 회귀를 포함한다.
DB fixture history는 명시적으로 만든 테스트 이력이다. 이번 새 테스트에서 Flyway를 실행했다는 증거는 아니다.
실제 Flyway 검증은 PR #58의 9개 테스트가 CI에서 유지된다.
실제 RDS TLS, host venv/SSM/IAM 설치, ECR pair, ALB와 운영 failover는 미검증이다. 현재 운영 적용은 NO-GO다.
