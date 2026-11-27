# 준비물 DB 독립 ECR 검사·실행 경로 검토 — Issue #311

2026-11-27 KST. #310 후 기존 DB를 처음 게시한 BASIC 검사에서 **High 18 / Medium 16 /
Low 4 / UNDEFINED 1 (39개 튜플)**를 확인하고 중단했다. 그중 기존 검토에 없던 CVE는
**15개(Medium 11 / Low 4)**다. High 15개가 추가된 것이 아니다.
기존 게시 이미지·실패 영수증은 보존하며, 이 PR은 추가 게시/서버 변경을 실행하지 않는다.

## 수정 버전과 15건의 영향

아래 링크는 Debian 공식 Security Tracker다. 설치된 trixie 패키지는 모두 미수정으로
기록되어 있다. sid 수정 버전을 trixie에 섞어 설치하지 않는다. upstream 수정 commit이나
merge request가 있다는 사실을 stable 패키지 제공으로 해석하지 않는다.

| CVE-2026 | ECR | 조건·영향 | 확인한 수정 버전 |
|---|---|---|---|
| [127039](https://security-tracker.debian.org/tracker/CVE-2026-127039) | Medium | GnuTLS 인증서 정보 재할당 실패 시 상태 미초기화, 메모리 압력하 DoS | upstream **3.8.14**, Debian unstable **3.8.14-2** |
| [127040](https://security-tracker.debian.org/tracker/CVE-2026-127040) | Medium | GnuTLS RSA-PSK ClientKeyExchange의 할당 실패 처리, DoS | 위와 같음 |
| [57062](https://security-tracker.debian.org/tracker/CVE-2026-57062) | Low | gpgsm CMS AES-GCM에서 지나치게 짧은 인증 태그 허용 | Debian unstable **2.4.9-5**부터 수정. upstream 최초 정식 릴리스 번호는 미확인 |
| [105712](https://security-tracker.debian.org/tracker/CVE-2026-105712) | Low | gpgtar가 기존 링크가 있는 디렉터리에 악성 archive를 풀 때 경로 밖 쓰기 | upstream **2.5.19**, Debian unstable **2.4.9-8** |
| [76781](https://security-tracker.debian.org/tracker/CVE-2026-76781) | Medium | libxml2 nextCatalog의 catalog 속성 누락, NULL 참조 | upstream **2.15.4**, Debian unstable **2.15.4+dfsg-1** |
| [86137](https://security-tracker.debian.org/tracker/CVE-2026-86137) | Medium | XML 정규식 문자 그룹 파싱 범위 밖 읽기 | 위와 같음 |
| [86141](https://security-tracker.debian.org/tracker/CVE-2026-86141) | Low | XML 정규식 parser 할당 실패 후 NULL 참조 | 위와 같음 |
| [137067](https://security-tracker.debian.org/tracker/CVE-2026-137067) | Medium | DTD 검증 reader 재사용 시 남은 검증 상태, use-after-free | **정식 수정 버전 미확인**, unstable 2.15.4도 미수정 |
| [137068](https://security-tracker.debian.org/tracker/CVE-2026-137068) | Medium | 재사용 reader의 faketext 잔존으로 use-after-free | 위와 같음 |
| [137069](https://security-tracker.debian.org/tracker/CVE-2026-137069) | Medium | 잘못된 XML 후 reader 상태 미초기화, 이후 문서 처리 DoS | 위와 같음 |
| [137072](https://security-tracker.debian.org/tracker/CVE-2026-137072) | Medium | current document 해제 후 reader 종료·DTD 상태 접근, use-after-free | 위와 같음 |
| [137073](https://security-tracker.debian.org/tracker/CVE-2026-137073) | Medium | reader 종료 뒤 NewWalker의 double-free, 잠재적 코드 실행 | 위와 같음 |
| [137074](https://security-tracker.debian.org/tracker/CVE-2026-137074) | Medium | NewWalker 뒤 ConstEncoding 호출의 NULL input, DoS | 위와 같음 |
| [137075](https://security-tracker.debian.org/tracker/CVE-2026-137075) | Low | 재사용 XML buffer의 종료 문자 누락, over-read·정보 노출/충돌 | 위와 같음 |
| [137082](https://security-tracker.debian.org/tracker/CVE-2026-137082) | Medium | SAX1 이후 같은 context를 다른 모드로 재사용할 때 속성 배열 NULL 참조 | 위와 같음 |

실제 설치 버전: `gnutls28 3.8.9-3+deb13u4`, `gnupg2 2.4.7-21+deb13u1`,
`libxml2 2.12.7+dfsg+really2.9.14-2.1+deb13u3`.
libxml2는 실질 2.9.14 계열이며 일부 CNA 설명의 도입 버전은 이보다 새롭다.
그러나 Debian의 trixie 판정은 vulnerable이므로 숫자만 비교해 오탐 처리하지 않는다.

## 실제 호출 경로와 변경

- PostgreSQL 16 실행 파일의 transitive `ldd` 결과에는 **libxml2·OpenSSL이 실제로 있다**.
  XML 함수를 호출할 수 있으므로 libxml2를 삭제하거나 안전하다고 단정하지 않는다.
  고정 AI 소스 `0920a3ada3b50700af61bf792c859c3606a4a1be`의 API/catalog/importer는
  JSON, 고정 SQL과 값 바인딩을 사용한다 (`catalog_store.py`의 `_execute`/`executemany`).
  XML reader/DTD/정규식 parser를 애플리케이션에서 직접 호출하지 않는다.
- 기존 DB에는 **gpgsm·dirmngr가 남아 있었고 gpgtar는 없었다**. DB 초기화·API·importer에서
  CMS/압축 해제/키서버 클라이언트가 필요하지 않으므로 세 실행 파일을 최종 이미지에서 제거한다.
  APT 서명·hash 확인이나 설치용 라이브러리를 제거하지 않는다. GnuPG source-package 검출은 남을 수 있다.
- PostgreSQL과 libldap의 실제 링크는 OpenSSL이며 GnuTLS가 없다. `check_db_runtime.py`는
  최종 DB 이미지의 transitive 링크에 GnuTLS가 새로 나타나거나 라이브러리 누락/경로 변경이 있으면 실패한다.
  GnuTLS가 설치되어 있다는 사실과 PostgreSQL 요청에서 호출된다는 사실을 구분한다.
  이는 임의의 컨테이너 프로세스 실행까지 차단하는 sandbox는 아니다.
- 실제 격리 DB에서 app의 superuser/createdb/createrole/replication/bypassrls 및
  서버 파일 읽기·쓰기/프로그램 실행 role이 모두 없는지 확인한다. 기존 내부 DB 포트 미공개,
  SCRAM 인증, LDAP 미사용, 자동 상품 수집/pg_cron job 비활성 조건을 유지한다.
- 실제 importer에 XML 외부 entity와 SQL 구문 모양의 합성 준비물 이름을 넣어 UTF-8 원문이
  TEXT로 보존되는지 검사한다. 이는 대표 입력 회귀이며 **CVE exploit 재현 또는 모든 SQL 주입 부재 증명은 아니다**.

DB 계정은 전용 DB의 owner이며 SQL XML 함수 실행을 금지한 계정은 아니다.
자격증명 탈취·SQL 주입·사용자 SQL 기능 추가·DB 공개 시 현재 제한 조건은 충분하지 않다.
**기존 Trivy libxml2 Critical 1건도 미수정**으로 유지하며 운영 수용 여부는 별도 승인 대상이다.

## 기존 24건과 검사 유지

DB 39건은 전체 tuple을 [ecr-security-review.json](ecr-security-review.json)의 `db`에 따로 기록한다.
나머지 24건은 다음 기존 분석을 재사용하며 패키지·버전·심각도는 DB 검사 자체로 대조한다.

- OpenSSL 14건 및 dpkg-source 1건: [ECR 검토](ECR-SECURITY-REVIEW.md).
  DB도 OpenSSL을 실제 사용하지만 QUIC/DTLS/DANE/CMS 입력 기능을 제공하지 않는다.
  pg_hba는 SCRAM, LDAP 없음. 사용자 TLS 인증서/CRL 업로드는 금지한다. dpkg-source 부재를 검사한다.
- libxml2 High 7건(74860, 86138/39/40/42/43/44) 및 GnuPG TPM High 1건(24882):
  [기존 보안 검토](SECURITY-REVIEW.md). XML 제한은 위와 같으며 tpm2daemon 부재 검사를 유지한다.
- Cyrus SASL High 1건(107161): [SASL 검토](SASL-SECURITY-REVIEW.md).
  플러그인·binary hash와 SCRAM/LDAP 경계 검사도 그대로 유지한다.

API 16건과 DB 39건은 별개다. gate에 역할별 저장소를 묶어 API 영수증을 DB 결과로
바꾸어 통과시키지 못하게 한다. registry·실행 manifest digest·전체 건수·중복·누락·pagination,
검사/취약점 feed 24시간 상한, exact CVE/package/version/severity, fix metadata 차단을 유지한다.
새 fixture는 과거 결과의 비식별 회귀 데이터이며 최신 후보의 ECR 검사 결과가 아니다.
만료는 기존과 동일한 **2026-12-01 00:00 KST**다. 패키지·호출 경로 변경 시 그 전에도 재검토한다.

## 다음 승인 범위

로컬 확인: Linux 단위 38개, 격리 PostgreSQL upstream 286개, 실제 API 인증 및 import/
replay/restart/권한/XML·SQL 원문 보존 검사가 통과했다. 새 이미지 runtime 통과 및 기존 이미지
거절도 확인했다. Trivy는 **Critical 1 / High 72**, 기존 exact review 밖 항목과 제공된
FixedVersion이 없었다. Spotless 통과. ECR 재게시·재검사와 GPU 시험은 아직 실행하지 않았다.
초기 합성 export의 정규 JSON 인코딩 실패 기록은 보존하고 계약에 맞춰 재실행했다.

로컬/CI/Codex 검증과 사용자 병합 후, 병합본과 동일한 **새 DB 후보 이미지**의
ECR 게시·독립 재검사만 승인받는다. 기존 DB 태그를 덮어쓰지 않고 실패 기록을 보존한다.
API 이미지는 그대로 유지하며 필요할 때 새 24시간 이내 검사 결과를 조회·대조한다.
기존 DB는 제거한 실행 파일이 남아 있어 새 runtime 검사를 통과하지 못하므로 재개 후보로 사용하지 않는다.
새 미검토 CVE·고정 버전 등장·검사 실패 시 중지한다.

GPU 기동, 운영 설치/동기화, STT/Haiku 활성화와 유료 호출은 포함하지 않는다.
`REVIEWED_RESIDUALS_ONLY`는 조건부 분류 검토이며 취약점 해결 또는 운영 승인이라는 뜻이 아니다.
