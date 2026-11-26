# CVE-2026-107161 영향 검토 — Issue #309

2026-11-27 KST. #308 병합본 API를 게시한 뒤 ECR BASIC에서 Cyrus SASL High 1건이
추가되어 **High 10 / Medium 5 / UNDEFINED 1**로 중단했다. 기존 실패 기록과 이미지는
보존한다. DB 전용 저장소 생성·게시·ECR 검사는 아직 진행하지 않았다.

## 수정 버전과 현재 이미지

공식 Debian tracker 기준:

| 배포판 | 버전 | 상태 |
|---|---|---|
| 현재 Debian 13 trixie | `2.1.28+dfsg1-9` | 미수정 |
| Debian 12 bookworm-security | `2.1.28+dfsg-10+deb12u1` | 수정 |
| unstable | `2.1.28+dfsg1-12` | 수정 |

trixie 수정은 point release 대상으로 기록돼 있으나 설치 가능한 수정 버전은 확인되지 않았다.
다른 배포판 패키지를 혼합하지 않는다. upstream PR #892의 수정과 정식 릴리스 버전은
구분하며, upstream 최초 수정 릴리스 번호는 여기서 확정하지 않는다.

결함은 `plugins/digestmd5.c`의 `add_to_challenge()`가 서버 realm/nonce 값을 인용 처리할 때
발생하는 클라이언트 heap overflow다. API·DB 모두 source package 메타데이터는 취약 버전이지만,
취약 코드가 들어 있는 **`libsasl2-modules`의 `libdigestmd5.so`는 설치되지 않았다.**
설치된 패키지는 `libsasl2-2`와 `libsasl2-modules-db`이며 플러그인은 `libsasldb.so*`뿐이다.
이는 패치 완료나 ECR 검출 오탐 선언이 아니라 **검토한 이미지의 도달 경로 제한**이다.

## 실제 호출 경로와 격리 검증

- 준비물 API: `psycopg[c] 3.2.13 → system libpq → libldap → libsasl2` 의존성이 존재한다.
  이전 #308의 시스템 OpenSSL 연결을 유지한다. 의존 라이브러리가 없다고 주장하지 않는다.
- 실제 DB 연결은 고정 `catalog-db:5432` PostgreSQL URI다. entrypoint는 PG 서비스 설정 변수를
  제거하고, 서버는 SCRAM-SHA-256을 사용한다. PostgreSQL SCRAM은 Cyrus DIGEST-MD5가 아니다.
- 격리 DB에서 SCRAM 비밀번호 저장 형식(값은 출력하지 않음), host SCRAM 규칙,
  LDAP 인증 규칙 부재와 실제 API 조회·동기화·286개 AI 테스트를 검증한다.
- `probe_sasl.py`: network none 컨테이너에서 Cyrus 메커니즘은 `EXTERNAL`뿐이며,
  `sasl_client_start(DIGEST-MD5)`는 `SASL_NOMECH(-4)`로 거절된다.
  합성 loopback PostgreSQL 서버가 DIGEST-MD5만 제시해도 실제 libpq가 지원하지 않는
  메커니즘으로 거절하고 인증 응답을 보내지 않는다. 취약 입력 공격 재현은 수행하지 않는다.
- 상품 공급자 경로는 HTTPX의 HTTPS 요청이며 LDAP/SASL DIGEST 인증을 사용하지 않는다.
  수집 worker는 기본 비활성이다. 코치·STT·외부 LLM 실행 코드와 설정은 변경하지 않는다.

`check_sasl_runtime.py`가 두 이미지의 패키지 버전·플러그인 목록·라이브러리 SHA-256을
고정 검사하고 API에서 실제 라이브러리 probe를 실행한다. DB는 같은 라이브러리와 플러그인
해시를 검증한다. 플러그인 추가·이동·누락, 바이너리 또는 패키지 변경은 재검토로 중단한다.
사용자 지정 플러그인 경로·라이브러리 마운트·LDAP 설정을 새로 허용하면 이 근거를 재사용하지 않는다.

## 검사 및 재개 경계

1. Dockerfile·AI source lock·빌드 입력은 변경하지 않는다. 기존 검증 API·DB digest를 보존한다.
2. CI의 필수 실행 경로인 `check_containers.py`가 런타임 SASL 검사와 격리 PostgreSQL 검사를 실행한다.
3. `ecr-security-review.json`에는 **API**의 정확한 CVE/package/version/severity 1튜플만 추가한다.
   ECR 원본 finding·심각도·실패 기록은 수정하거나 숨기지 않는다. DB role은 계속 미검토로 차단한다.
4. `check_ecr_scan.py`는 스캔 영수증만 검사한다. 실행 가능성 검증을 대신하지 않으므로 게시 재개 전
   동일 이미지로 런타임 검사와 격리 계약 검사 통과를 별도로 확인해야 한다.
5. 기존 OpenSSL 등 잔여 조건과 **2026-12-01 00:00 KST** 만료를 유지한다. 새 CVE·버전·수정 정보가
   나오면 중단하며, 현재 시점의 vendor 정보와 실제 ECR 전체 결과를 다시 검토한다.

병합 후 검토본 동일성·CI·digest를 확인하고 **이미 게시된 API 동일 digest의 ECR 재검사**부터
별도 승인받는다. 통과하면 DB 전용 저장소 생성과 기존 DB 이미지 게시·재검사를 승인 범위에 포함할 수 있다.
DB 결과는 별도로 검토해야 하며 API 통과로 대신하지 않는다. 추가 게시·GPU 기동·운영 변경·유료 호출은
이번 구현에 포함하지 않는다. 잔여 취약점 0건 또는 운영 배포 승인으로 해석하지 않는다.

## 공식 근거

- [Debian CVE tracker와 배포판별 수정 버전](https://security-tracker.debian.org/tracker/CVE-2026-107161)
- [Debian libsasl2-modules 파일 목록](https://packages.debian.org/trixie/amd64/libsasl2-modules/filelist)
- [Cyrus upstream 수정 PR](https://github.com/cyrusimap/cyrus-sasl/pull/892)
- [PostgreSQL SASL 인증](https://www.postgresql.org/docs/16/sasl-authentication.html)
- [Cyrus 메커니즘 열거 API](https://www.cyrusimap.org/sasl/sasl/reference/manpages/library/sasl_listmech.html)
- [기존 OpenSSL·잔여 항목 검토](ECR-SECURITY-REVIEW.md)
