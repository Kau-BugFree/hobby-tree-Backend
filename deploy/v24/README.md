# V18 → V24 승인형 전환 준비 (Issue #164)

이 디렉터리와 `scripts/deploy/v24_*.py`는 수동 운영 도구다. PR/Push/일반 애플리케이션 시작은 이 도구를 호출하지 않는다. 이 PR의 병합은 배포 승인이 아니다. 기존 V1~V24 SQL, API 계약, `ddl-auto=validate`, 런타임 Flyway 비활성화는 유지한다.

## 검토 대상과 범위

- 기존 설치된 `ec2-release.py`를 SHA-256으로 고정한 라이브러리로 사용한다. 기존 `execute`/rollback 경로는 호출하지 않는다. 실제 설치 파일이 다르면 중단한다.
- 기존 V18 가드 및 90번 drop-in, 이전 요청 기록을 보존하고 새 95번 drop-in이 시작·정지 훅을 대체한다. 기존 active 기록을 별도 보관한 뒤 공유 배포 gate를 `v24-*` 상태로 유지해 구형 배포 명령을 차단한다.
- 새 `V24MigrationCli`가 포함된 후보 JAR/이미지를 사용한다. 기존 b3ac5ad 후보 이미지는 이 CLI가 없어 재사용할 수 없다.
- DB 관리 계정은 백엔드로 복사하지 않는다. 마이그레이션은 기존 제한 계정, 백업은 EC2 역할, AI 설정은 보호된 파일로만 처리한다. DB/JWT/Redis 파일을 새로 조회하거나 갱신하지 않는다.
- 이번 코드에는 신규 스키마 SQL, AWS 리소스 생성, OAuth/CORS 변경, 유료 모델 호출을 포함하지 않는다.

## 적용 전 준비물

1. CodeRabbit/CI가 통과한 PR을 사용자가 Merge Commit으로 병합한다. 병합 소스에서 linux/amd64 이미지를 빌드하고 정확한 이미지로 검증한다. 이미지 발행은 별도 승인 후 기존 백엔드 ECR에만 일반 push한다. OCI revision, ECR digest, config ID, JAR SHA와 소스 SHA를 기록한다.
2. 로컬에서 `python3 scripts/deploy/prepare-v24-bundle.py --repository <checkout> --jar <candidate.jar> --catalogue <catalog-apply.review.sql> --output <new-directory> --revision <source-sha>`로 검토 번들을 만든다. 이 명령은 DB/AWS에 접근하지 않고 승인서도 생성하지 않는다. 원본 SQL의 SHA가 다르면 거부한다.
3. 실행 plan은 `v24_protocol.validate_plan`의 정확한 필드를 채운다. `bundle-manifest.review.json`은 실행 plan이 아니다. 실제 대상의 owner/특권 로그인 목록, 기존 파일/active 기록 hash, AI 파일 hash와 이미지 식별자를 재확인해 바인딩한다. `database-policy.tsv`는 첫 줄 owner, 이후 정렬된 특권 로그인 목록이다. 역할을 추측하지 않는다.
4. 모든 plan/bundle/approval 파일은 Linux root 소유, 쓰기 권한 0600/0500의 보호된 경로에 설치한다. 파일뿐 아니라 모든 상위 디렉터리의 소유자/쓰기 권한/심볼릭 링크를 검사한다. `bundle`에는 실행할 도구, drop-in, 호스트 도구, history/policy/catalogue의 정확한 SHA를 포함한다.
5. 기존 공개키에 대응하는 승인 키로 **사용자가 승인한 단계만** 서명한다. JSON 필드는 `format:1`, `action:hobbytree-v18-v24`, `request`, `phase`, `plan_sha256`, `approval_id`(새 32자리 hex), `issued_at`, `expires_at`(epoch seconds)이다. 수명은 최대 2시간이며 시작 시 5분 이상 남아야 한다. `openssl dgst -sha256 -sign <protected-key> -out <signature> <approval.json>`의 입력 바이트를 그대로 보관한다. 이전 승인/서명을 재사용하지 않는다.
6. 백엔드 호스트 내부의 기존 migration 자격증명 경로로 `/run/hobbytree/migration-secrets/{DB_USERNAME,DB_PASSWORD}`를 준비한다. 디렉터리 UID 10001/0500, 두 파일 UID 10001/0400, 상위 디렉터리 root 보호를 확인한다. CLI에는 파일만 read-only mount하며 비밀번호는 환경변수/인자/출력에 넣지 않는다. 기존 자격증명 경로를 사용할 수 없다면 권한 확대를 자동 수행하지 않고 별도 적용 범위를 확인한다. history/policy/catalogue는 비밀이 아닌 승인된 파일로, root-only 임시 디렉터리의 0444 복사본을 개별 read-only mount해 UID 10001 JVM이 읽게 한다.

## DB 쓰기 차단과 복구 준비

관리 호스트의 기존 보호된 owner 연결을 이용하고, 그 자격증명을 로컬이나 백엔드에 출력/복사하지 않는다. 작업 대상은 `hobbytree` DB 한 곳이다. 아래 작업도 별도 승인 대상이다.

- 먼저 owner/특권 로그인 및 DB 전체 ACL, app/migration 역할의 직접·상속 권한, 현재 세션을 읽고 승인 기록에 넣는다. 제한된 migration 역할이 public의 테이블/시퀀스를 소유하고 app으로 SET ROLE 가능한지 확인한다. 예상 밖 소유권/역할이면 자동 변경하지 않는다.
- `quiesce`로 ALB 요청을 drain하고 백엔드를 정지한 뒤 관리 연결에서 `REVOKE CONNECT ON DATABASE hobbytree FROM hobbytree_app RESTRICT;`를 별도 트랜잭션으로 실행한다. 기존 PUBLIC CONNECT가 없어야 하며 migration/검토된 관리 역할 외 일반 로그인에 유효 CONNECT가 없어야 한다. 세션 종료나 추가 역할 권한 철회는 이 명령에 포함하지 않는다. 남은 세션/상속 권한이 있으면 중단하고 실제 범위를 다시 검토한다.
- owner 연결을 닫고 `verify-fence`를 실행한다. 앱 포함 다른 클라이언트 세션 0개, prepared transaction/활성 subscription/확인되지 않은 백그라운드 writer가 없어야 한다. 관리자는 이 점검을 우회할 수 있으므로 이 기간 다른 관리 작업을 금지한다.
- backup/migrate/configure 동안 fence를 유지한다. 읽기 전용 이력 역할의 CONNECT는 계속 철회된 상태로 둔다.
- activate 직전에 원래 app의 직접 CONNECT 1개만 `GRANT CONNECT ON DATABASE hobbytree TO hobbytree_app;`로 복원한다. 원래 grantor/grant-option/전체 ACL과 일치하는지 관리 연결에서 비교하고 연결을 닫는다. app CONNECT를 제외한 ACL fingerprint가 다르면 활성화를 거부한다. **원래 ACL을 재확인하지 않은 상태로 위 SQL을 실행하지 않는다.**
- V23이 만든 coach 테이블 2개와 해당 시퀀스만 앱에 CRUD/USAGE·SELECT를 부여한다. 앱에 Flyway 이력 쓰기/소유권/CREATE를 부여하지 않는다.

## AI 설정 전달

기존 AI 서버의 보호된 토큰을 사용한다. 백엔드의 `/etc/hobbytree/ai.properties`에 추천/코치 enabled, 승인된 사설 IP의 8000/8003 URL과 필요한 토큰을 설치한다. 토큰은 채팅·명령 인자·SSM 출력·Git에 넣지 않는다. 기존 비공개 서버 내부 전송 경로를 사용할 수 없으면 대상 서버가 생성한 일회성 공개키로 암호화해 전달하고 대상에서만 복호화한다. 원본/런타임 파일 권한과 해시 및 서버 측 토큰 일치를 확인한 후 임시 전달물을 제거한다. 이 단계는 운영 파일 쓰기이므로 적용 승인에 포함한다.

`configure`는 기존 root 파일을 검증하고 `ai.properties`만 런타임 UID 10001/0400 파일로 추가한다. 기존 DB/JWT/Redis 비밀번호 파일과 Redis 컨테이너/볼륨을 그대로 유지한다. 추천/코치 `/health` 통과는 토큰이 필요한 실제 추천/대화 성공을 뜻하지 않는다. 실제 E2E는 따로 승인·검증한다.

## 단계와 실행

각 단계는 보호된 번들에서 다음 형식으로 실행한다.

```
sudo python3 -B -I <bundle>/v24_transition.py <phase> <bundle>/plan.json <approval.json> <signature>
```

| 순서 | phase | 효과 / 확인 |
| --- | --- | --- |
| 1 | prepare | 원본 파일, 구형 가드, 현재 healthy 이미지, 새 이미지/JAR/SQL, V18 이력/권한 확인. V18 active gate 유지 |
| 2 | quiesce | 새 gate 고정, 자동 시작 비활성화, ALB 제외/drain, 백엔드 정지, Redis 유지 |
| 3 | verify-fence | 위의 별도 owner CONNECT 철회 후 일반 writer 차단 증거 저장 |
| 4 | backup | 차단 후 생성한 암호화 RDS 수동 snapshot이 available인지 확인 |
| 5 | migrate | snapshot/fence 재확인, V19~V24 6개 적용, 정확한 checksum/24행, 앱 권한/JPA validate |
| 6 | configure | 새 가드/95 drop-in, host validator/fetch/contract, 이미지 설정, AI 파일만 추가 |
| 7 | activate | 앱 CONNECT 복원 후 ACL/스키마 검증, 일회성 boot ticket으로 기동, ALB 제외 상태의 health |
| 8 | publish | ALB 등록 후 최소 150초 health 안정 확인 |
| 9 | catalogue | 별도 승인한 SQL을 단일 트랜잭션으로 적재, 동일 데이터는 no-op, 충돌은 거부 |

한 번 사용한 approval ID는 재사용할 수 없다. 미완료/실패 단계는 자동 재시도하지 않는다. `restart`는 complete 상태에서 새 승인을 받아 동일 이미지를 drain/정지/검증/기동하는 경로다. **자동 재시작과 부팅 시 자동 시작은 계속 꺼져 있다.** 프로세스 장애·EC2 재부팅 시 무인 복구는 이 전환 범위에 포함하지 않으며 원인/현재 상태 확인 후 별도 복구 승인이 필요하다.

## 실패 및 복구

- 두 상태 파일 갱신 전에 대상 상태와 이전 파일 해시를 durable intent로 기록한다. 서명/번들/plan 검증 후 배포 lock을 얻은 수동 단계만 기록된 중간 상태를 복구한다. intent와 실제 파일이 일치하지 않으면 거부하며 systemd 시작 hook은 복구하지 않는다. 완료 상태 저장 중 중단은 저장만 마무리하고, running/failed 상태는 그대로 유지해 단계 재실행이나 승인 재사용을 허용하지 않는다.
- DB 변경이 시작된 뒤 V18 이미지로 자동 롤백하지 않는다. 단계 실패는 증거를 보존하고 ALB 제외/서비스 정지를 시도해 결과를 기록한다. 강제 종료 시 running 기록을 남기며 후속 단계가 차단된다. 별도 운영자가 ALB/컨테이너 상태를 확인해야 한다.
- catalogue는 트랜잭션이므로 실패 시 행 변경은 rollback된다. 정상 서비스는 이 실패만으로 정지하지 않는다. sequence 번호는 PostgreSQL 특성상 소모될 수 있다.
- snapshot 복구는 새 RDS 대상이 필요한 별도 복구 작업이다. 기존 DB를 삭제하거나 V24 SQL을 되돌리지 않는다. 새 요청을 만들려고 기존 gate/journal/ticket을 삭제하면 안 된다.
- stop/cleanup은 해당 systemd invocation의 정확한 container ID/image/request/nonce만 정지한다. 교체된 컨테이너나 Redis를 정지하지 않는다. 공유 journal 불일치가 있어도 이 정지 검증은 독립적으로 수행한다.

## 검증과 승인 묶음

- 로컬/CI: Spotless, 일반 Gradle, root Linux Python 회귀, 외부 네트워크 없는 PostgreSQL의 V18→V24/권한/이력변조/데이터보존/rollback. 실제 catalogue SQL은 별도 로컬 fixture로 3/18/386/1313/1850행 및 replay/drift를 확인한다. CI에 사용자 원본 데이터를 넣지 않는다.
- CI의 기존 Docker/TLS PostgreSQL/Redis/auth/Flyway/Gitleaks 및 CodeRabbit 결과는 PR별 최신 커밋으로 확인한다. 이 코드가 AWS에서 실제 실행된 것으로 보고하지 않는다.
- 적용 승인은 **A 이미지 발행·보호된 번들/설정 전달**, **B 점검 시간·CONNECT 차단/복원·snapshot·V19~V24·가드/이미지 교체·ALB 재등록**, **C catalogue 3,570행**, **D 시연 계정으로 온보딩/추천/대화 실제 DB 쓰기 E2E**로 나눈다. 기존 AWS 자원 사용 외 인프라 증설/권한 확대/유료 모델은 포함하지 않는다.
