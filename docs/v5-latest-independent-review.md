# V5→V18 독립 검토 목록 — RDS 소유권 수정

현재 실행 기준은 [전환 runbook](v5-latest-transition-runbook.md)과 [읽기 전용 SQL](../scripts/ci/inspect-v5-transition-readonly.sql)이다. 과거 format2/DBowner=migration/자동 app CONNECT 설명은 폐기했다.

- scripts/deploy/schema-transition.py: format3, reviewed owner/privileged LOGIN policy, 여섯 승인 단계, 신규 snapshot→guard retirement→migration, host lock/dual journals/hash/containment.
- src/main/java/com/hobbytree/hobbytree/deployment/SchemaMigrationCli.java: DBowner 유지, object ownership/schema/sequence/제한 역할, CONNECT DDL 없음, 정확한 guard/트랜잭션 lock/history, app SET ROLE/JPA.
- scripts/ci/prepare-schema-transition.py: offline Git/JAR SQL/CRC/test evidence, 명시적 owner/inventory, 구 선언 거부.
- scripts/ci/render-transition-document.py 및 deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json: exact helper hash, phase/ACK regex, ENV_VAR, root 경로/파일 검사, timeout.
- scripts/ci/inspect-v5-transition-readonly.sql: 전체 권한/객체/ACL/guard/history/세션/준비 트랜잭션/확장 읽기 검사. 미확인 상태를 추정하지 않는지 확인.
- src/test/java/com/hobbytree/hobbytree/migration/FlywayMigrationTests.java: DBowner postgres와 별도 비관리자 migration fixture, 외부 ACL 운영자, guard 보존/퇴역/실패, 권한 부재/unknown admin, V5→V18/JPA.
- scripts/tests/test_schema_transition.py: snapshot/phase 순서/불완전 migration/서명 replay/외부 CONNECT activation/containment.
- scripts/ci/verify-migration-test-results.py 및 scripts/ci/rehearse-upgrade.ps1: 전체 migration test가 skip 없이 실행되는지 확인.

기존 ec2-release.py, v45_gate.py, SSM v2/V45 JSON, Flyway V1–V18은 변경하지 않는다. 로컬 PostgreSQL 검증이 실제 RDS 권한 검증을 대신하지 않는다. AWS 실제 적용 NO-GO, V20 통합 실패는 별도 blocker다.

설치 준비 추가 검토: scripts/deploy/schema-install.py, scripts/ci/build-schema-install-bundle.py, scripts/ci/extract-schema-install.py, scripts/ci/schema-phase-approval.py, scripts/tests/test_schema_install.py 및 [설치 운영 절차](v18-ssm-installation.md). 신규 문서의 Python -B와 installer의 bytecode 차단, archive/manifest/공개키 독립 hash, 기존 파일 덮어쓰기 거부, 공용 lock/비종결 phase에서 승인서 반입과 일회용 marker 보존을 확인한다.
