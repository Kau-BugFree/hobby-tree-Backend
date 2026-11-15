# develop 검증 → main 승인형 마이그레이션·배포

Issue #66 / Draft PR #67. 이 문서는 구현 계약이며 운영 설치 완료를 뜻하지 않는다.

## 실행 흐름

1. 기능 PR은 develop으로 병합한다. develop push의 Spotless, Docker/PostgreSQL/Redis/API, Flyway 검사가 실제 develop SHA에서 성공해야 한다.
2. 사용자가 develop→main PR을 Merge Commit으로 병합한다. main은 최초 확인 시 초기 커밋 `5b57792912d909f829117f68f4b36e11c669abd4`였다. 최초 릴리스에는 전체 develop 변경 검토가 필요하다.
3. main push가 `Backend Main Release`를 시작한다. PR의 출처 저장소·develop head·main Merge Commit과 develop의 정확한 SHA에 달린 GitHub Actions 결과를 확인한다. main 병합 SHA에서 다시 테스트하고 이미지를 빌드한다. develop에서 만든 이미지를 main 이미지인 것처럼 사용하지 않는다.
4. 실제 Git SQL, 이미지 내부 SQL, 격리 DB의 Flyway 이력/CRC, Docker config ID, SHA 및 검증한 image.tar를 하나의 manifest에 연결한다. V24부터 새 버전까지 격리 업그레이드를 재현한다. 기준 SHA는 도입 시의 검증 하한이며 운영 현재 버전은 호스트의 완료 이력·실행 이미지·실제 DB로 다시 확인한다. 다음 버전마다 숫자를 바꾸지 않는다.
5. `HOBBYTREE_MAIN_RELEASE_ENABLED=true`와 보호된 `hobbytree-main-production` Environment가 모두 준비되어야 AWS 작업이 실행된다. 승인 전에는 이미지 파일만 Actions artifact로 준비한다. 현재 설치·활성화하지 않았다.
6. 지정 운영자 `rladnqls14`가 GitHub에서 승인한다. main 브랜치만 허용하고 관리자 bypass를 끈다. 자기 실행을 자신이 승인하는 것을 허용할 수 있으며 팀원 승인을 추가로 강제하지 않는다. 이 승인과 PR 리뷰/병합은 별개다.
7. 승인된 job은 최신 main SHA와 보호 설정을 다시 확인한 뒤 정확한 이미지 artifact를 ECR에 게시하고 고정 SSM 문서 버전 1을 호출한다. 호스트는 설치된 검토본만 실행한다. Actions는 임의 shell·호스트 도구 덮어쓰기·DB Secret·DB 관리자 권한을 받지 않는다.
8. 호스트 공유 lock → 실제 전임 이미지/설정/SQL 검증 → ALB drain → 앱 정지 → 일반 앱 CONNECT 차단/다른 세션 없음 확인 → 암호화 RDS snapshot available 확인 → pending SQL을 단일 Flyway 그룹 트랜잭션으로 적용 → 실제 이력/JPA 검증 → CONNECT 복구 → 새 앱 기동 → 내부 health/JWT/CORS 확인 → ALB 공개/150초 공개 검증 순으로 실행한다. Redis와 AI 서버는 교체하지 않는다. 유료 AI 요청과 실제 계정 쓰기 E2E는 호출하지 않는다.

## SQL와 권한 계약

- 기존 V1~V24 원문을 수정하지 않는다. 정수 연속 버전만 허용하고 중복·누락·파일명 변경·기존 바이트 변경·SQL 설정 sidecar/callback은 차단한다. 반복 마이그레이션, 비트랜잭션 DDL과 자동 repair/clean/baseline은 지원하지 않는다. 이런 변경은 별도 설계가 필요하다.
- `ReleaseMigrationCli`는 앱 시작과 분리된다. 앱은 계속 `SPRING_FLYWAY_ENABLED=false`, `ddl-auto=validate`다.
- 마이그레이션 역할은 기존 제한 역할을 유지한다. 승인된 소유자 설치 SQL `deploy/main-release/install-fence.sql`이 추가하는 것은 별도 `release_control` 스키마의 고정 앱 역할 CONNECT 토글뿐이다. DB명/역할을 임의 인자로 받지 않으며 PUBLIC 실행 권한은 없다. 배포 역할에 superuser/DB owner/CREATEROLE를 주지 않는다.
- 신규 business table/sequence 권한은 migration 소유 객체에만 부여한다. `flyway_schema_history`는 앱에 쓰기 권한을 주지 않는다.
- 배포 외부 privileged writer는 이 DB fence로 막을 수 없다. 운영 시간대에 별도 관리자/배치 작업이 없어야 하며 감지되는 세션은 전환을 차단한다.

## 실패 처리

- 사전 검증 실패는 실행 중인 앱을 정지하지 않는다. 실패 증거와 차단 상태를 남긴다.
- 변경된 스키마의 migrate가 시작되기 전 실패, 또는 동일 SQL의 이미지 교체 실패는 현재 이력 확인 후 이전 이미지 복원을 시도한다. 원래 실패는 journal에 보존하고 Actions는 실패한다.
- migrate 시작 뒤에는 실패/timeout을 모호한 상태로 취급한다. 앱/ALB를 격리하고 후속 배포를 차단한다. Flyway 그룹 트랜잭션의 정상 rollback과 관측 실패는 다르므로 자동 DB 다운그레이드나 무조건 이전 이미지 재기동을 하지 않는다.
- SSM/Actions timeout 또는 강제 종료 시 원격 작업이 계속될 수 있다. 새 run을 즉시 재실행하지 않는다. 호스트 lock, 요청/journal, 실행 컨테이너, 실제 Flyway 이력과 snapshot을 대조한 뒤 복구 계획을 별도 승인한다. 상태 파일 삭제로 복구하지 않는다.
- snapshot 복구는 새 RDS 생성/연결 전환이 필요한 별도 작업이다. 배포 후 새 쓰기가 있으면 snapshot으로 되돌릴 때 데이터가 사라질 수 있으므로 자동 복원하지 않는다.
- 고정 SHA tag가 이미 게시되었다면 새 이미지로 덮어쓰지 않는다. 실패 후 같은 릴리스 재게시/재실행은 이력·이미지 증거를 먼저 정리한다.

## 최초 운영 도입: 별도 승인 필요

아래는 아직 실행하지 않은 설치 항목이다. 현재 V24 guard를 배포 job이 우회하거나 자동 제거하지 않는다.

1. 실제 운영 V24 이미지 digest/config/revision/JAR SQL/DB 이력, EC2/ALB/RDS/역할/AI/CORS와 Secret mount 소유권을 조회하여 검토 기록을 만든다. `config.example.json`의 값을 사실로 채운다. 예시 placeholder를 그대로 사용하면 안 된다.
2. DB 소유자가 제한된 CONNECT 토글을 설치하고 일반 앱·다른 역할의 호출 거부를 확인한다. 스키마/기능 설치 자체는 운영 DB 쓰기이므로 별도 승인 대상이다.
3. 최종 병합된 `main_release_host.py`를 `/opt/hobbytree/scripts/main-release/`에 root 소유·비쓰기 권한으로 설치한다. `/etc/hobbytree/main-release.json`은 root:root 0600, 실제 executor SHA와 모든 보호 파일 해시를 기록한다. 실행 파일을 workflow가 원격 갱신할 수 없어야 한다.
4. 기존 V24 journal/plan/drop-in과 원래 unit을 그대로 백업·보존한다. 유지보수 시간에 기존 systemd 서비스를 stop/disable하고 새 실행 경로로 현재 이미지를 재기동한다. root 보호 `deployments/active.json`을 `main-release-owned` 상태로 넘겨 구형 release가 동시에 작동하지 못하게 한다. 같은 공유 lock을 사용하며 신규 `main-release/active.json`에는 실제 실행 이미지와 `jar_history` 결과, `status=complete`를 기록한다. 이 인계는 자동 스크립트로 무조건 실행하지 않는다.
5. 현재 Compose가 AI Secret mount·CORS·리소스·네트워크를 유지하고 backend restart=no인지 확인한다. 초기 도입 이후에도 무인 부팅/프로세스 재시작은 이 PR의 범위 밖이다.
6. `HobbyTree-Main-Release` SSM 문서 v1을 설치한다. parameter ENV_VAR interpolation을 지원하는 SSM Agent여야 한다. IAM은 고정 문서·기존 백엔드 인스턴스에만 SendCommand를 허용한다. ECR 게시와 SSM 역할은 제안 JSON으로 검토하고 max session duration 7200초를 설정한다. ECR immutable, EC2의 기존 ECR read·ALB·RDS snapshot 권한도 확인한다. GitHub에는 DB 비밀번호를 복사하지 않는다.
7. main 전용 Environment, 운영자 승인, bypass 금지, OIDC subject, GitHub Actions API 조회 권한, 활성화 변수를 설정한다. 실제 설정 적용 전까지 모든 AWS job은 비활성 상태다.
8. 도입의 예상 중단 시간은 직전 확인한 ALB drain 300초 + 앱 기동/점검을 포함해 약 6~8분이며, 실제 수치는 적용 직전 다시 측정한다. 스키마 변경 배포에는 snapshot 대기와 migration 시간이 추가되므로 같은 시간을 보장하지 않는다.

## 검증

- Python: main 출처/Environment/요청 검증, 동일 SQL/추가 SQL 분기, 단계 순서와 durable 기록, 백업 실패/기동 실패/마이그레이션 실패, 중복 요청, JAR SQL 변조.
- 격리 PostgreSQL: 실제 V24→fixture V25·V26, 데이터 보존, 앱 권한과 소유자 기능 접근 거부, writer 존재, checksum 변조, 반복 실행, 실패한 V27 포함 그룹 전체 rollback, 같은 스키마 교체.
- 기존 Docker/TLS PostgreSQL/Redis/auth, 모든 Flyway 회귀, Spotless, Gitleaks, Actions 문법을 검사한다. 실제 운영 배포 성공과 혼동하지 않는다.
- 과거 V5 artifact ID `13384572345`는 2026-11-15 조회 시 HTTP 404다. V5 회귀 테스트는 PR마다 실행하되 원본 실물 검사는 수동 실행으로 분리한다. V5 게시 도구의 원본 필수 검증과 실패 시 게시 금지는 유지한다. 현재 릴리스는 자신의 run에서 새로 검증한 artifact만 사용한다.
- 현재 GitHub ruleset 필수 검사는 Spotless, required approvals는 0이다. 사용자 요청에 따라 관련 Docker/Flyway/보안 검사와 리뷰도 확인한다. classic branch protection은 연결 권한 403으로 미확인이다. CONTRIBUTING의 팀원 승인 원칙과 실제 ruleset/사용자 운영 기준의 차이는 유지한다.

## 별도 승인 범위

A: Environment/OIDC/IAM/SSM·소유자 토글·호스트 도입 및 검증. B: 특정 main SHA의 ECR 게시·snapshot·DB migration·이미지 교체·공개 검증. C: 실제 계정 쓰기 또는 유료 AI 호출은 별도 요청. 이 PR의 코드 검증 승인으로 A/B/C를 대신하지 않는다.
