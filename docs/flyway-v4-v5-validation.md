# Flyway V4·V5 검증과 이미지 전환 검토

검토일은 2026-11-05이며 기준 develop은 `aacb97b4264b7fe591fd25699df7e4c979360265`다.
Issue #57의 테스트·문서 작업이다. 운영 상태는 사용자 제공 정보인 V1~V3 성공, V4/V5 미적용을
기준으로 한다. 실제 AWS/운영 DB/Secret/개인정보는 조회하지 않았다.

## 판정

| 대상 | 판정 | 조건 |
|---|---|---|
| V4·V5 원문 SQL의 운영 적용 | CONDITIONAL GO | 원래 V3 스키마·소유권·권한·백업·잠금 창을 확인하고 별도 migration 계정으로 실행 승인 |
| 기존 버전의 V5 스키마 사용 | CONDITIONAL GO | 재빌드한 구버전 JPA의 validate/기존 OPEN 읽기 성공. COMING_SOON을 구버전이 읽는 경로는 차단 필요 |
| 구버전의 COMING_SOON 읽기 | NO-GO | 구버전 enum에 값이 없어 실제 Hibernate 읽기에서 IllegalArgumentException 재현 |
| 현재 배포 도구로 V3 JAR → V5 JAR 교체 | NO-GO | DB 적용 이후에도 migration-set SHA 비교가 다르므로 서비스 교체 전에 거부됨 |

SQL·업무 코드·EC2 설정·배포 보호 코드는 이번 PR에서 수정하지 않았다. 배포 도구의 조건부
이미지 전환 지원은 아래 명시한 후속 승인 작업이며, 이번 PR은 검증 테스트와 테스트 CI를 추가한다.

## 실제 검증 환경과 명령

- Java 21, 프로젝트 Flyway Core/PostgreSQL 모듈 `12.4.0`, PostgreSQL `17.11` (`server_version_num=170011`).
- Docker Desktop Linux Engine 소켓이 없어 로컬 Docker 테스트는 실행할 수 없었다. 기존에 확보한
  PostgreSQL 바이너리를 ASCII 임시 디렉터리에 복사해 새 클러스터를 시작했다. localhost 임의 포트는
  이번 실행에서 `8081`이었고, control DB는 `hobbytree_migration_ci`, 테스트 스키마는 `public`이다.
- 전용 setup 계정이 UUID 이름의 임시 DB/role을 생성한다. 실제 Flyway는 non-superuser migration
  owner로 실행하고, 별도 app role로 권한 및 JPA를 검사한다. 각 테스트가 만든 DB/role만 정리한다.
  기존 DB_URL, AWS Secret, 기존 Backend/Redis 컨테이너를 사용하지 않는다.
- 테스트 클래스는 `MIGRATION_INTEGRATION_TEST=true`일 때만 활성화된다. JDBC URL은
  `jdbc:postgresql://127.0.0.1:<port>/hobbytree_migration_ci`만 허용한다. 테스트 전용 username/password는
  프로세스 환경으로 전달하며 출력하지 않는다.

실행한 Gradle 명령은 다음과 같다. `--rerun-tasks`를 포함해 이전 skip/캐시가 검증을 생략하지 않게 한다.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 spotlessApply test --tests '*FlywayMigrationTests' --rerun-tasks
.\gradlew.bat --no-daemon --max-workers=2 bootJar
.\gradlew.bat --no-daemon --max-workers=2 test spotlessCheck bootJar --rerun-tasks
```

일반 테스트는 222개 중 106개 통과, opt-in 통합 테스트 116개 skip, 실패 0개였다. 위의 migration
전용 실행은 별도로 9개 모두 실행·통과했으며 일반 테스트의 skip을 성공으로 계산하지 않았다.
Spotless와 bootJar도 통과했다. 검증 후 이번 작업이 만든 서버의 PID·바이너리·data 경로를 대조하고
그 전용 PostgreSQL만 종료했다. GitHub Docker job의 결과는 PR Checks에서 별도로 확인한다.

CI에는 기존 Docker Validation과 독립된 `Flyway V1-V5 upgrade PostgreSQL 17` job을 추가했다.
기존 `deploy/base-image-lock.json`의 PostgreSQL 17.11 digest를 사용해 새 컨테이너 하나를 시작한다.
포트는 runner의 `127.0.0.1:15432`, 데이터는 임시 tmpfs, 비밀번호는 실행마다 생성·마스킹한다.
기존 Backend/Redis 서비스를 기동하거나 교체하는 테스트는 이 job에 포함하지 않는다.
테스트 XML의 9개 실행/0 skip/0 failure/0 error를 별도로 확인하고 전용 PostgreSQL만 정리한다.

## 검증 결과

로컬 PostgreSQL 17.11의 테스트 9개가 통과했다. 신규 설치와 업그레이드 모두 실제 Flyway를 사용했다.

| 항목 | 결과 |
|---|---|
| A. 빈 DB V1→V5 | 5개 순차 적용, 이력·script·installed_by·checksum 일치, 최종 strict validate 성공 |
| B. V3→V5 | 기존 18개 테이블 모두 데이터를 채운 후 2개 적용. 기존 모든 행/컬럼/인덱스/다른 제약 동일 |
| C. 재실행/변조 | 재실행 migrationsExecuted=0. 별도 복사본 V4의 checksum 변조는 validate 거부, 원본·이력 유지 |
| D. content_reports | 모든 컬럼 NOT NULL, migration owner, identity/default 상태, PK와 idx_reports_dedupe 확인 |
| E. listing_status | 기존 DRAFT/OPEN/CLOSED/CANCELLED 유지, V5 이후 COMING_SOON 허용, 잘못된 값·NULL 거부 |
| F. 신고 유효성 | enum/길이/Unicode/FK/NOT NULL 위반 거부, user 삭제 시 cascade, app 계정 삽입 확인 |
| G. 잠금/실패 | V4 users writer 차단, V5 read lock과 충돌, 실패 시 이력·DDL 유지 및 원인 제거 후 재시도 성공 |
| 최신 JPA | 전체 Entity의 Hibernate validate 및 다섯 listing status 읽기 성공 |

`idx_reports_dedupe`는 `(reporter_user_id, target_type, target_id, reason, created_at DESC)`의 일반
인덱스다. 대상 존재 여부와 24시간 중복 방지는 ReportService의 공개 활동 검사·user row lock·조회로
처리한다. DB는 SPONSORED_CONTENT와 동일 신고의 중복 행을 허용하며 target_id의 FK도 두지 않는다.
이는 V4 주석과 서비스 설계에 명시된 경계다. 기존 ReportIntegrationTests의 HTTP 검증과 함께 평가한다.

검증한 Flyway checksum은 SHA256이 아닌 signed CRC checksum이다. 운영 이력과의 대조는 승인된
마이그레이션 절차에서 수행해야 한다.

| 버전 | 로컬 checksum |
|---|---:|
| V1 | 1798696397 |
| V2 | -776995949 |
| V3 | -1230142336 |
| V4 | -498665831 |
| V5 | 687484878 |

## 오류와 실패 동작

| 구분 | 확인 내용 |
|---|---|
| 환경 준비 | 한글 경로 initdb의 UTF8 `0xbb` 오류는 ASCII 임시 경로로 해결. 기존 캐시에 pg_isready/createdb가 없어 JDBC로 전용 control DB 준비 |
| 테스트 구현 | 사용 불가능한 AssertJ 메서드를 수정. 중간 V3/V4 validate의 Pending 기대값과 VARCHAR 길이 오류 기대값을 실제 동작에 맞춰 수정 |
| 예상 거부 | 42501 앱 신규 테이블 권한 부족, 23503 FK, 23502 NOT NULL, 23514 CHECK, 22001 VARCHAR(1000) 초과 |
| 예상 이관 실패 | 55P03 lock timeout, 42P07 기존 content_reports 충돌, 42704 기존 CHECK 이름 부재, 23514 기존 비정상 상태 데이터 |
| 원본 SQL 결함 | 정상 V3 스키마/데이터에서 V4/V5 적용 오류는 발견되지 않음 |

V5가 DROP 이후 ADD CHECK에서 실패하는 경우도 검증했다. fixture의 CHECK를 의도적으로 변경하고
UNKNOWN 행을 만든 뒤 V5를 실행하면 23514가 발생한다. 실패 후 이전 CHECK와 행이 복구되고 V5의
성공/실패 이력 행이 남지 않는다. fixture를 바로잡은 후 V5 재시도가 성공한다. 운영 데이터 수정은 아니다.

Flyway 12.4의 독립 validate는 해석한 V4/V5가 Pending이면 이를 오류로 보고한다. 중간 테스트의
적용 이력 검증만 `*:pending`을 허용하며 checksum 검사는 유지한다. V5 완료 후에는 기본 strict
validate를 실행한다. 운영의 과거 validate 성공만으로 새 JAR의 파일·checksum 검증을 대신할 수 없다.

## 구버전과 롤백

앞서 제공된 digest `sha256:42c00bd9efb941f0c782358a98840e42cc121d9b27627412ee5d971cc4a51ba4`는
[GitHub ECR Push run](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/41318688429)의
push 로그에서 `be9375a8dde209c300e592c41f9c4addd8ebc793`와 연결됐다. 이번 작업에서 실시간 EC2
digest는 조회하지 않았으므로 운영 이미지가 달라졌으면 해당 소스로 다시 검토해야 한다.

그 Git 소스를 별도 checkout에서 bootJar로 빌드하고, 그 버전의 실제 Entity 클래스와 Hibernate를
사용해 localhost의 V5 DB를 읽는 일회성 JPA probe를 실행했다.

```text
LEGACY_ENUM=[DRAFT, OPEN, CLOSED, CANCELLED]
LEGACY_SCHEMA_V5_WITH_COMING_SOON_VALIDATE=PASS
LEGACY_OPEN_READ=PASS
LEGACY_COMING_SOON_READ=EXPECTED_ILLEGAL_ARGUMENT
```

이는 구버전 ORM의 schema validate/enum 읽기 검증이다. 실제 ECR Linux 컨테이너, EC2, 전체 구버전
HTTP 서버를 실행한 결과는 아니다. be9375a8의 ExperienceRepository는 선언만 있고 당시 운영 서비스의
사용 경로가 확인되지 않아 모든 기존 API가 즉시 실패한다고 판단하지 않는다. 구버전 JPA가 해당 행을
읽는 상황과 안전한 롤백 보장은 구분해야 한다.

V4는 기존 테이블의 컬럼을 삭제하지 않고 V5는 기존 네 상태를 계속 허용한다. COMING_SOON 행을
생성하기 전의 구버전 사용은 조건부 호환이다. 새 코드/다른 writer가 COMING_SOON을 저장하면 이전
이미지의 enum 읽기는 안전하지 않다. 현재 `src/main` 소스에서는 해당 상태를 읽는 알림 신청 경로가 있고,
그 값을 생성하는 공개 API는 확인되지 않았다. 관리자/적재 작업 등 외부 writer도 확인해야 한다.
새 값의 쓰기는 모든 reader 업그레이드와 롤백 기간 판단 뒤 별도로 승인한다.

이미지 롤백은 DB 스키마·신고 데이터·세션·업무 쓰기를 되돌리지 않는다. 데이터가 남은 상태에서
content_reports DROP 또는 COMING_SOON→DRAFT 일괄 변경을 복구 절차로 실행하면 안 된다.

## 권한과 PostgreSQL 잠금

- V4의 FK 생성은 참조 users에 ShareRowExclusiveLock을 획득하며 writer의 RowExclusiveLock과
  충돌한다. 실제 원문 SQL을 전용 DB transaction에서 실행해 users UPDATE의 55P03을 확인했다.
- V5의 DROP/ADD CHECK는 experiences에 AccessExclusiveLock을 요구한다. 읽기 transaction의
  AccessShareLock과 충돌하며 ADD CHECK는 기존 행을 검사한다. 잠금은 transaction 종료까지 유지된다.
- 실제 운영 row 수, 장기 transaction, lock 대기/scan 시간은 미검증이다. 승인된 저부하/점검 창과
  lock_timeout/statement_timeout을 운영 데이터 규모에 맞춰 결정하고 실패 시 중단·재시도한다.
- V4 migration 계정은 public CREATE 및 users REFERENCES 권한이 필요하다. V5는 experiences의
  소유자 또는 그 소유 역할로 실행할 수 있어야 한다. users 소유 확인이나 UPDATE 권한만으로는 부족하다.
- 기존 business table GRANT는 새 content_reports에 자동 적용되지 않았다. app role에 필요한
  SELECT/INSERT를 명시적으로 부여해야 한다. table default privileges를 사용하는 경우에는 V4 실행
  전에 migration owner 역할의 `ALTER DEFAULT PRIVILEGES`를 설정해야 한다. V4 이후 설정은 이미
  생성된 content_reports에 소급 적용되지 않으므로 별도 `GRANT`로 SELECT/INSERT를 부여해야 한다.
  V4의 IDENTITY 자동 INSERT/RETURNING은 sequence USAGE가 없어도 성공했고, 같은 app role의
  직접 nextval 호출은 42501로 거부됐다. 이 차이도 회귀 테스트로 확인했다. 현재 경로에 불필요한
  sequence 권한을 일괄 부여하지 않는다. 실제 app 역할과 별도 sequence 직접 사용 여부를 확인한다.
  이 권한을 GitHub 배포 역할이나 앱의 Flyway history 쓰기 권한으로 확대하지 않는다.

운영자 승인 후 대조할 SQL은 다음과 같다. 이번 작업에서는 운영 DB에 실행하지 않았다.

```sql
SELECT current_user, current_database(), current_schema();
SELECT installed_rank, version, script, checksum, success
FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT schemaname, tablename, tableowner FROM pg_tables
WHERE schemaname='public' AND tablename IN ('users','experiences','content_reports');
SELECT conname, convalidated, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid='public.experiences'::regclass AND conname='experiences_listing_status_check';
SELECT to_regclass('public.content_reports'), to_regclass('public.idx_reports_dedupe');
SELECT listing_status, count(*) FROM public.experiences GROUP BY listing_status;
```

## 현재 이미지 보호 검사와 후속 변경안

실제 로컬 구/신 JAR에 `schema_fingerprint()`를 실행했다. 기존 V1~V3 파일 내용은 동일하고 새 JAR에
V4/V5가 추가됐다.

```text
old V1~V3 SHA256 = 65c88b864b804ed8ecc76e18c13a4d4d51c509cab0bbbe688a4c05a884f93903
new V1~V5 SHA256 = 84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026
CURRENT_GUARD_ACCEPTS=False
```

`ec2-release.py`의 prepare는 JAR 파일 집합을 비교하고 RDS history는 조회하지 않는다. 따라서 DB를
V5로 먼저 적용해도 현재 도구의 결과는 동일하다. 동일 digest no-op 성공은 이 변경 집합 승인과 별개다.

후속 작업은 기존 prepare의 기본 동일성 검사를 유지하면서 **승인된 추가 migration만 검증하는
좁은 전환 경로**를 별도 Issue/리뷰에서 설계하는 것이다. 이번 PR에는 구현하지 않았다.

| 파일/설정 | 후속 최소 범위 |
|---|---|
| scripts/deploy/ec2-release.py | 기본 equality 유지. 다른 집합은 root 전용 승인 증거가 있을 때만 검토된 append-only 전환인지 검증 |
| 승인 증거 | 이전/후보 digest·소스 SHA·DB/schema·V1~V5 파일 및 history checksum·성공/validate·승인자·유효기간·일회 사용을 결합. V1~V3 변경/삭제와 승인 외 migration은 거부 |
| scripts/ci/ec2-deploy.py / 수동 Workflow | migration 실행과 이미지 전환 승인을 분리. 단순 ALLOW_MIGRATIONS 플래그로 통과시키지 않음 |
| deploy/ssm/HobbyTree-Dev-DeployBackend.json | 바뀐 host SHA를 재생성하고 실제 AWS 버전 확인 후 새 고정 문서 버전으로 전환. 현재 저장소 pin은 2 |
| app/마이그레이션 설정 | EC2 Flyway=false/ddl-auto=validate 유지. 별도 Flyway runner만 migration 계정 사용. 앱 신규 테이블 권한과 sequence 직접 사용 필요 여부 확인 |
| 회귀 테스트 | 승인 부재/위조·만료·재사용, digest/history/checksum 불일치, 기존 SQL 변경/삭제, 승인 외 추가를 차단. Redis/ALB/rollback 기존 검증 유지 |

승인 증거는 운영자가 별도 migration 계정으로 검증·발급하는 제어다. GitHub 배포 역할에 migration
Secret 조회나 SQL 실행 권한을 추가하지 않는다. 정확한 증거 형식과 실제 버전은 후속 설계에서 확정한다.
마이그레이션 파일을 이미지에서 제외하거나 history를 repair/baseline하여 비교를 맞추는 방안은 사용하지 않는다.

## 승인과 실행 순서

1. 테스트/CI/리뷰를 확인하고 사용자가 테스트 PR의 병합을 판단한다. 이 병합으로 AWS 배포를 실행하지 않는다.
2. 운영자가 기존 digest/소스, V1~V3 checksum, 원래 CHECK/상태값, users/experiences 소유권과 앱 권한,
   백업의 시점·복구 가능성, 장기 transaction/DDL 창을 확인한다. 불일치는 NO-GO다.
3. 위 좁은 이미지 전환 경로를 별도 승인·구현·격리 검증한다. 동일성 보호 제거와 새로운 RDS 생성은 포함하지 않는다.
4. 별도로 migration 실행을 승인한다. 검토된 V1~V5와 Flyway 12.4, 별도 migration 계정으로
   info/적용 이력 checksum 확인 → 정확히 V4/V5만 Pending 확인 → target 5 migrate → strict validate/info
   순서를 사용한다. 비밀값은 보호된 일시 설정으로 전달한다.
5. 각 SQL은 PostgreSQL transaction으로 처리된다. V5 실패 시 V4는 성공 상태로 남을 수 있다.
   lock/권한/스키마 원인을 확인하고 동일 원문으로 재시도한다. clean/repair/baseline/수동 history 수정은 사용하지 않는다.
6. 완료된 history/checksum·새 테이블/index/권한·다섯 상태 CHECK를 검증한 뒤 이미지 전환 승인을 별도로 받는다.
   Digest CAS, Redis ID/전체 mounts, Docker health, ALB healthy 150초, 이전 이미지/설정 보존과 실패 롤백을 유지한다.
7. 모든 reader 및 복구 이미지 호환성을 확인한 뒤 COMING_SOON 데이터 생성과 신규 기능 사용을 승인한다.
8. 장애 시 이미지/설정 복구와 DB 데이터 복구를 구분한다. 기존 이미지 복귀 전 새 enum 데이터와 신구 코드
   읽기 호환성을 확인한다. 예외적인 데이터 복구는 별도 승인으로 다루며 이번 계획에서 신규 RDS를 만들지 않는다.

운영 잠금 시간·실시간 데이터·RDS 권한·새 후보 ECR 이미지·실제 SSM/EC2 배포 및 실제 이미지 롤백은
미검증이다. 격리 SQL 검증의 성공을 이 운영 조건의 승인으로 대신하지 않는다.

## 근거

- [PostgreSQL 17 ALTER TABLE 잠금과 CHECK 검증](https://www.postgresql.org/docs/17/sql-altertable.html)
- [V4 SQL](../src/main/resources/db/migration/V4__add_content_reports.sql)
- [V5 SQL](../src/main/resources/db/migration/V5__add_experience_coming_soon_status.sql)
- [격리 테스트](../src/test/java/com/hobbytree/hobbytree/migration/FlywayMigrationTests.java)
- [기존 배포 절차](ec2-image-deployment.md)
