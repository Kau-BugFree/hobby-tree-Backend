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
5. 현재 Compose가 AI Secret mount·CORS·리소스·네트워크를 유지하고 backend restart=no인지 확인한다. 재부팅/장애 복구를 활성화하려면 [runtime-recovery.md](runtime-recovery.md)의 별도 설치·승인 절차를 함께 적용한다.
6. `HobbyTree-Main-Release` SSM 문서 v1을 설치한다. parameter ENV_VAR interpolation을 지원하는 SSM Agent여야 한다. IAM은 고정 문서·기존 백엔드 인스턴스에만 SendCommand를 허용한다. ECR 게시와 SSM 역할은 제안 JSON으로 검토하고 max session duration 7200초를 설정한다. ECR immutable, EC2의 기존 ECR read·ALB·RDS snapshot 권한도 확인한다. GitHub에는 DB 비밀번호를 복사하지 않는다.
7. main 전용 Environment, 운영자 승인, bypass 금지, OIDC subject, GitHub Actions API 조회 권한, 활성화 변수를 설정한다. 실제 설정 적용 전까지 모든 AWS job은 비활성 상태다.
8. 도입의 예상 중단 시간은 직전 확인한 ALB drain 300초 + 앱 기동/점검을 포함해 약 6~8분이며, 실제 수치는 적용 직전 다시 측정한다. 스키마 변경 배포에는 snapshot 대기와 migration 시간이 추가되므로 같은 시간을 보장하지 않는다.

## 검증

### 이미지 간 SQL 줄바꿈 비교

이전 이미지에서 추출한 전체 이력은 저장된 `active.migrations`와 raw SHA256까지 정확히 같아야 한다. 새 이미지의 적용 완료 구간은 순서·파일명·CRLF/CR→LF 정규화 SHA256·Flyway CRC로 비교한다. 따라서 Windows/ Linux 패키징 줄바꿈 차이는 허용하지만 SQL 내용, 공백, 빈 줄, 파일명 변경은 거부한다. 새 이미지 전체 fingerprint는 승인 manifest와 같아야 한다. Git의 기존 SQL 수정 금지 검사도 유지한다.

### preflight 실패 기록을 보존한 수동 정리

`scripts/deploy/reconcile_main_preflight.py`는 **운영 적용 승인을 별도로 받은 뒤** root가 한 번 실행하는 도구다. workflow/SSM 문서에서 자동 호출하지 않는다. 새 이미지를 배포하거나 기존 앱을 재시작하지 않고, 검증된 이전 완료 상태만 `main-release/active.json`에 복원한다.

대상은 journal에 `preflight/failed` 이벤트 **하나만** 남은 실패다. drain 이후·migration 시작·중단된 running 기록·모호한 기록은 거부하며, DB rollback/repair/unfence로 대체하지 않는다.

1. GitHub 활성화 변수와 호스트 `enabled`, `runtime.enabled`를 false로 유지한다. 기존 backend 및 main-runtime service/timer가 disabled/inactive이고 ALB·현재 앱이 정상인지 확인한다. GitHub 변수는 운영자가 별도로 확인하며 도구가 GitHub 설정을 변경하지 않는다.
2. 검토·병합된 SHA의 `main_release_host.py`와 `reconcile_main_preflight.py`를 `/opt/hobbytree/scripts/main-release/`에 root 소유·쓰기 제한으로 설치한다. Git blob/설치 바이트 SHA256을 대조하고 `executor_sha256` 및 `runtime.sources.host`를 함께 갱신한다. 설치·설정 변경도 별도 승인 범위다. 런타임 활성화는 포함하지 않는다.
3. 실패 요청의 `request.json`, `journal.json`, `previous.json`, blocked `active.json`을 별도로 보관한다. 독립 사전 확인 기록과 비교하여 아래 세 SHA256을 고정한다. 현재 파일 해시를 확인 없이 그대로 승인 값으로 사용하지 않는다. DB 앱 계정 CONNECT, 실제 Flyway 이력 및 Secret 준비 상태도 읽기 전용으로 확인한다.
4. 승인한 request ID·해시로 실행한다. 다음은 값이 채워지지 않은 **계획용 예시**다. 도구는 인접한 실행기를 import하며 신뢰되지 않은 PYTHONPATH/user site 주입은 `-E -s`로 제외한다.

   ```sh
   sudo python3 -E -s /opt/hobbytree/scripts/main-release/reconcile_main_preflight.py \
     --request APPROVED_RUN_ID-ATTEMPT \
     --active-sha256 APPROVED_BLOCKED_ACTIVE_SHA256 \
     --journal-sha256 APPROVED_FAILED_JOURNAL_SHA256 \
     --previous-sha256 APPROVED_PREVIOUS_SHA256
   ```

5. 동일 release lock 아래 설정·설치 해시·이전 이미지·후보 이미지 SQL·DB `before` 검사(이력/권한 정책 SELECT), 내부/public health·인증·CORS·ALB healthy, backend/Redis ID·시작 시각·재시작 횟수·mount·boot ID 불변을 확인한다. 후보 이미지는 기존 V24에 없는 generic DB checker 실행에만 사용한다. ECR pull/login, 임시 검사 컨테이너와 로컬 증거 파일 생성은 있지만 앱 교체·ALB 등록 변경·DB migration은 없다.
6. `main-release/reconcile-<request>/intent.json`과 `verified.json`이 남고, 원래 실패 디렉터리는 바이트 단위로 보존된다. 임시 ECR 자격증명을 제거하고 `active.json`을 마지막으로 저장한다. 성공 후에도 모든 활성화 설정은 false다.
7. 검증 실패 시 blocked 상태를 유지한다. 증거 디렉터리가 이미 있으면 재실행하지 않는다. 마지막 저장의 fsync/응답 실패는 성공 여부가 모호할 수 있으므로 `verified.json`의 기대 해시와 실제 active·기존 실패 파일을 대조하여 수동 판단한다. 실패 디렉터리/감사 기록을 삭제하거나 request ID를 바꾸어 재시도하지 않는다.
8. 정리 성공은 운영 배포 승인이 아니다. 수정 코드를 develop 검증 → main Merge Commit으로 승격하고 새 SHA의 CI·이미지·계획을 확인한 후 배포를 별도 승인한다. 이미 ECR에 게시한 실패 SHA의 immutable 태그를 덮어쓰거나 원래 workflow를 무조건 재실행하지 않는다. C 자동 복구 활성화·재부팅은 별도다.

회귀 테스트는 실제 JAR의 CRLF/LF 호환·내용 변조 거부, 실패 증거/해시/단계/설정 검증, 공유 flock, 검증 실패 시 blocked 유지, 감사 기록 선행·active 마지막 저장을 검사한다. Linux의 fsync/flock 테스트는 네트워크 없는 컨테이너에서도 실행 가능하며 기존 PR Docker CI의 전체 Python discovery에 포함된다.

### 재개 검증 자체가 실패한 경우

`failed.json`은 `stage`(preflight, 내부/공개 probe, ALB, 전후 상태, 증거 검증, 최종 저장)를 기록한다.
`host.check`는 preflight의 이미지·SQL·Compose·DB 실행/성공 표식/정리 단계를 구분한다.
`host.database`에는 명령 성공 여부, 정확한 성공 표식 존재·마지막 위치 여부, 정리 결과 및 허용 목록의 오류 종류만 남긴다.
예외 메시지·명령 인자·stdout/stderr·환경변수·Secret 원문은 저장하거나 출력하지 않는다.
DB 실행과 컨테이너 정리가 모두 실패하면 원래 실패와 정리 오류를 함께 보존한다.
DB 성공 표식 및 SQL/권한 검증 조건을 완화하지 않는다. `progress.json`은 마지막 진단 체크이며 성공 증명이 아니다.

기존 `reconcile-<request>`는 시도 1로 유지한다. 실패 원인 검토 및 새 설치/재시도 승인 후에만
`--attempt 2 --prior-audit-sha256 APPROVED_AUDIT_SHA256`으로 `reconcile-<request>-attempt-2`를 새로 만들 수 있다.
원래 request ID와 active/journal/previous 승인 해시는 그대로 사용한다. 요청 ID를 바꾸거나 기존 디렉터리를 삭제하지 않는다.
시도는 연속된 1~10만 허용한다. 같은 시도 번호의 재실행·번호 건너뛰기는 거부한다.

- 앞선 시도 모두의 intent 입력 해시와 원래 실패 요청/이전 완료 상태가 일치해야 한다.
- 각 감사 디렉터리는 모든 파일 바이트·경로·권한·디렉터리 목록을 SHA256으로 고정하고 다음 intent에 연결한다.
  symlink/hardlink/특수 파일/쓰기 가능한 비신뢰 경로는 거부한다. 변경된 실행기/설정은 새 intent에 따로 기록한다.
- `failed.json`이 없거나 `verified.json`이 있거나 임시 ECR 자격증명이 남아 있으면 자동 후속 시도를 허용하지 않는다.
  마지막 저장/fsync 실패는 실제 active와 verified의 기대 해시를 별도로 대조한다. 실패 표시만으로 재실행하지 않는다.
- 운영자의 읽기 전용 해시 확인은 기존 명령의 request/세 승인 해시에 `--inspect-attempt 1`을 추가한다.
  이 모드는 공유 lock 아래 감사 해시만 출력하고 Host 검사·DB 검사·파일 생성을 실행하지 않는다.
  출력 해시는 별도 보관한 감사 자료와 대조한 후 승인 값으로 고정한다.
- 후속 시도는 DB `before`, 서비스/ALB/HTTP 검사 전체를 다시 수행한다. 승인된 감사 해시를 마지막으로 다시 확인하고
  모든 검사 성공 후에만 active를 복원한다. 앞선 실패 기록은 바이트 단위로 유지한다.
- 컨테이너 전후 비교는 Docker 마운트 배열의 순서만 정규화한다. 마운트 항목의 모든 필드와 개수는 그대로 비교한다.
- 재개 성공은 배포 성공이 아니다. B의 새 main 이미지 게시·스냅샷·마이그레이션·배포와 C 활성화·재부팅은 별도다.

PR #208 적용 후 발생한 기존 ValueError는 상세 단계가 없는 구형 기록이다. 이번 진단 구현/격리 테스트 통과를
그 운영 실패의 원인 확정이나 운영 재개 성공으로 보고하지 않는다. 후속 실행도 단계 실패 시 중단·보고한다.

### 공개 복구를 완료한 drain 중단의 재개

기본 재개 명령은 계속 preflight 실패만 허용한다. `--drain-recovery-sha256`을 명시한 경우에만
`drain/running` 이벤트 하나로 끝난 요청을 별도로 검증한다. stop·fence·backup·migrate 등 다른 이벤트가
있거나 snapshot 기록이 있으면 거부한다. 실행 중 원격 명령의 취소와 종료를 확인하고 공유 lock을
확보해야 한다. 호스트 배포·runtime 및 관련 unit이 비활성인 상태에서만 진행한다.

운영자의 읽기 전용 사전점검에서 `recover-main-B-<request>-before-migrate` 디렉터리 전체의
바이트·경로·권한 해시를 별도 백업과 대조해 고정한다. 이 디렉터리는 기존 공개 복구가 남긴
`intent.json`과 `verified.json`을 포함해야 한다. 도구가 복구 영수증을 새로 만들거나 수정하지 않는다.
intent의 원래 request·journal·previous 및 blocked active 해시가 현재 입력과 같고,
verified가 이전 DB 버전·공개 복구·무재시작·snapshot 없음과 원래 증거 보존을 증명해야 한다.
기존 명령의 request 및 세 승인 해시에 이 복구 디렉터리의 승인 해시를 추가한다.

```text
sudo /usr/bin/python3 -B -E -s /opt/hobbytree/scripts/main-release/reconcile_main_preflight.py \
  --request APPROVED_RUN_ID-ATTEMPT \
  --active-sha256 APPROVED_BLOCKED_ACTIVE_SHA256 \
  --journal-sha256 APPROVED_DRAIN_JOURNAL_SHA256 \
  --previous-sha256 APPROVED_PREVIOUS_SHA256 \
  --drain-recovery-sha256 APPROVED_RESTORATION_AUDIT_SHA256
```

이 경로도 기존 재개와 동일한 이미지·SQL·DB `before` 이력/권한, 현재 컨테이너·boot·ALB·HTTP 검사를
모두 수행한다. 복구 영수증을 마지막에 다시 대조하고 원본 journal과 앞선 감사 기록을 유지한 채
새 재개 디렉터리에 검증을 기록한 다음 active만 마지막에 복원한다. 성공 응답은
`DRAIN_RECONCILED_NO_RELEASE_RETRY`다. 이미지 교체·ALB 변경·CONNECT 변경·마이그레이션을 실행하지 않는다.
재개 검증이 실패한 뒤 새 시도를 준비할 경우 동일 복구 해시를 유지하고 기존 `--inspect-attempt`,
`--attempt`, `--prior-audit-sha256` 절차를 따른다. 이 명령 자체의 운영 실행은 별도 승인 대상이다.

B 최종 Redis 검증과 재개 관찰은 같은 마운트 정규화 함수를 사용한다. 전체 마운트 항목과 중복을
유지하고 배열 순서만 무시한다. 실제 필드·개수 또는 Redis ID가 다르면 검증을 차단한다.

- SSM `ReleasePayload.allowedPattern`은 본문 1~4094자의 base64 문자와 끝의 `=` 0~2개를 허용한다. `{1,4094}`는 실제 SSM CreateDocument에서 `invalid repeat count`로 거부되어, 1~1000 / 1001~2000 / 2001~3000 / 3001~4000 / 4001~4094 길이 구간을 각각 표현한다. 각 반복 횟수는 1000 이하이며, 독립 길이 구간으로 나누어 가변 길이 반복을 연속 배치하지 않는다.
- `GOTOOLCHAIN=local go test -v scripts/tests/ssm_pattern_test.go`는 실제 문서의 정규식을 Go 엔진으로 컴파일하고, 기존 파서 오류 재현·모든 길이 경계·문자/패딩 제한을 검사한다. 표준 라이브러리만 사용하며 PR/develop 및 main 검증 job에서 실행한다. [Go 반복 횟수 제한](https://pkg.go.dev/regexp/syntax), [SSM 파라미터 문서](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-syntax-data-elements-parameters.html).
- 이 검사는 AWS에 접근하지 않는다. SSM 서비스의 문서 생성 성공 확인은 검토본 병합 후 승인된 비활성 설치에서 수행한다. 수정 문서를 로컬에서 검증했다고 운영 설치 완료로 간주하지 않는다. `ENV_VAR`, 고정 실행기 경로 및 호스트 payload 검증은 유지한다.

- Python: main 출처/Environment/요청 검증, 동일 SQL/추가 SQL 분기, 단계 순서와 durable 기록, 백업 실패/기동 실패/마이그레이션 실패, 중복 요청, JAR SQL 변조.
- 격리 PostgreSQL: 현재 최신 스키마→다음 두 버전의 fixture, 데이터 보존, 앱 권한과 소유자 기능 접근 거부, writer 존재, checksum 변조, 반복 실행, 실패한 세 번째 버전 포함 그룹 전체 rollback, 같은 스키마 교체. 실제 최신 버전 다음에 fixture 번호를 계산하므로 신규 SQL과 충돌하지 않는다.
- V24 전용 구형 CLI 회귀는 현재 스키마가 V24일 때만 함께 실행한다. V25 이후에는 고정 V24/JPA 계약을 가진 구형 실행기를 적용하지 않으며 결과에 `legacy_v24_applicable=false`를 명시한다. 버전 공통 CLI의 7개 격리 검사(두 번 연속 릴리스 포함)는 항상 필수이며, main에서는 도입 하한 V24부터 실제 최신 SQL까지 별도 전체 업그레이드/JPA 검사도 실행한다.
- 기존 Docker/TLS PostgreSQL/Redis/auth, 모든 Flyway 회귀, Spotless, Gitleaks, Actions 문법을 검사한다. 실제 운영 배포 성공과 혼동하지 않는다.
- 과거 V5 artifact ID `13384572345`는 2026-11-15 조회 시 HTTP 404다. V5 회귀 테스트는 PR마다 실행하되 원본 실물 검사는 수동 실행으로 분리한다. V5 게시 도구의 원본 필수 검증과 실패 시 게시 금지는 유지한다. 현재 릴리스는 자신의 run에서 새로 검증한 artifact만 사용한다.
- 현재 GitHub ruleset 필수 검사는 Spotless, required approvals는 0이다. 사용자 요청에 따라 관련 Docker/Flyway/보안 검사와 리뷰도 확인한다. classic branch protection은 연결 권한 403으로 미확인이다. CONTRIBUTING의 팀원 승인 원칙과 실제 ruleset/사용자 운영 기준의 차이는 유지한다.

## 별도 승인 범위

### 검증된 develop 커밋을 고정한 릴리즈

일반 경로는 develop → main Merge Commit이다. 운영자가 후속 기능/마이그레이션을 이번 배포에서
제외하기로 선택하면 `deploy/<실제 Issue 번호>-<영문-kebab-case>` 브랜치로 검증된 커밋을 고정할 수 있다.
브랜치 이름만으로 배포 권한이 생기지 않는다. main의 두 부모 Merge Commit, 같은 저장소의 병합 PR,
PR head와 두 번째 부모 SHA 일치, 해당 SHA의 Spotless/Docker/Flyway GitHub Actions 성공을 모두 확인한다.

고정 head는 **동일 저장소에서 develop으로 실제 병합된 PR의 정확한 head 또는 merge SHA**여야 하고,
현재 develop에도 포함된 조상 커밋이어야 한다. PR 연결/조상 증거가 누락되거나 미병합·다른 저장소·
임의 추가 커밋·다른 SHA의 체크이면 거부한다. 고정 브랜치에 새 수정이 필요하면 먼저 그 정확한 head를
develop 대상 PR에서 검증·병합한 다음 고정 릴리즈를 갱신한다. 아직 병합되지 않은 수정 head로
main을 먼저 병합하면 provenance 검사가 실패한다.

고정 브랜치에는 제외하기로 한 후속 develop 변경을 자동 merge/cherry-pick하지 않는다.
main에서만 실행, 최신 main SHA, Environment 운영자 승인, OIDC 및 호스트 가드는 그대로 유지한다.
고정 릴리즈 지원 자체는 AWS/운영 설정 변경이나 배포 승인이 아니다.

A: Environment/OIDC/IAM/SSM·소유자 토글·호스트 도입 및 검증. B: 특정 main SHA의 ECR 게시·snapshot·DB migration·이미지 교체·공개 검증. C: 실제 계정 쓰기 또는 유료 AI 호출은 별도 요청. 이 PR의 코드 검증 승인으로 A/B/C를 대신하지 않는다.
