# ECR 추가 검출·OpenSSL 영향 검토 — Issue #307

후속 #311: DB는 별도 게시·첫 ECR 검사 후 미검토 15건에서 중단했다.
현재 DB 검토·새 후보 승인 기준은 [DB 독립 검토](DB-ECR-SECURITY-REVIEW.md)를 따른다.
아래의 DB 미게시 표기는 #307 당시 이력이다.

후속 #309: #308 게시 후 추가된 CVE-2026-107161과 현재 16건 검토는
[SASL 검토 문서](SASL-SECURITY-REVIEW.md)를 따른다. 아래 15건은 이전 검사 시점 기록이다.

2026-11-27 KST. #306의 API 게시 후 BASIC 검사: **High 9 / Medium 5 / UNDEFINED 1**.
이 중 High 7은 기존 Trivy 기준에 없었다. 기존 게시 이미지/중단 기록을 보존한다.
DB는 미게시·ECR 미검사이며 API 결과를 DB 검사 통과로 대체하지 않는다.

## 수정 버전과 조치

OpenSSL 공식 11/10·11/17·11/24 공지의 아래 14개 항목은 **3.5.10** 수정 대상이다.
공개 소스에는 수정됐지만 정식 릴리스는 **2026-12-01 예정**이다. Debian trixie의
`3.5.9-1~deb13u1`은 아직 vulnerable이다. 수정 예정 버전을 설치 가능 버전으로 쓰지 않는다.
검사 심각도(ECR High 등)를 upstream Low로 바꿔 숨기지 않는다.

실제 이미지 검사에서 `psycopg-binary==3.2.13`이 **OpenSSL 3.5.4**를 별도 포함한 것을 확인했다.
OS 업데이트만으로 이 복사본은 갱신되지 않는다. 드라이버 API 버전 3.2.13을 유지하고
`psycopg[c]`를 분리 builder에서 컴파일하여 Debian `libpq5`/`libssl3t64`에 연결한다.
C 가속 구현은 유지하며 gcc/libc 개발 헤더·wheel/pip는 production에 남기지 않는다.
이는 오래된 번들 제거와 업데이트 경로 통합이며 **OpenSSL 14건 패치 완료는 아니다**.
코치/STT·AI 소스·DB Dockerfile/SQL/운영 데이터는 변경하지 않는다.

## ECR 전체 15건

범위는 고정 AI `0920a3ada3b50700af61bf792c859c3606a4a1be`와 현재 private Compose다.
아래는 호출 경로 분석이며 취약점 자체의 부재나 공격 재현 시험을 의미하지 않는다.

| CVE (모두 2026) | ECR | 조건·실제 영향 | 수정 |
|---|---|---|---|
| 88823 | High·신규 | AES-GCM-SIV 분할 AAD. 앱에서 해당 암호 API를 사용하지 않는다. | OpenSSL 3.5.10 예정 |
| 121482 | High·신규 | CMS/PKCS7 검증+CRL 확인. 해당 메시지 입력/검증 기능 없음. 기본 Python SSL CRL flag도 꺼져 있음 확인. TLS 검증 자체는 유지. | 동일 |
| 107493 | High·신규 | PEM 읽기에 기존 non-NULL X509_INFO stack 전달. 앱의 직접 호출 없음; 공식 공지상 OpenSSL 내부 CA loader는 NULL 사용. | 동일 |
| 84786 | High·신규 | DANE TLSA 조합. Uvicorn HTTP/TCP, libpq TCP, HTTPX 기본 TLS에서 DANE 설정 없음. | 동일 |
| 107496 | High·신규 | 공격자 EC 수신 인증서에 CMS 암호화. 앱에 CMS 암호화/인증서 업로드 없음. | 동일 |
| 95692 | High·신규 | AES-GCM-SIV 잘못된 IV 길이. 해당 암호 API 사용 없음. | 동일 |
| 95691 | High·신규 | QUIC 재전송 use-after-free. QUIC/HTTP3 사용 없음. | 동일 |
| 107494, 124709 | High·기존 2건 | QUIC 메모리 누적. QUIC 사용 없음. | 동일 |
| 121481 | Medium | EVP_EncodeBlock 약 2GiB 입력. 직접 호출 없음; HTTP 입력은 AI에서도 64KiB 상한, 메모리 제한도 유지. | 동일 |
| 88822 | Medium | QUIC peer 자동 탐지. QUIC 사용 없음. | 동일 |
| 95690 | Medium | DTLS fragment 메모리. DTLS 사용 없음. | 동일 |
| 35190 | Medium | TLS 1.3 + 명시적 Suite B 인증서 flag. 해당 설정 없음. 일반 TLS 전체를 끄지 않는다. | 동일 |
| 84785 | UNDEFINED | QUIC stream 재조립 메모리. 분류 미정도 검사에서 제외하지 않음. | 동일 |
| 91094 | Medium | dpkg-source의 악성 quilt 소스 압축 해제. 두 production 이미지에 dpkg-source 없음을 검사. 사용자 소스 패키지 처리 없음. | dpkg **1.23.12 (sid)**; trixie **1.22.22 미수정** |

API는 DB catalog 조회만 수행하며 상품 수집 worker는 승인 전 비활성이다.
일반 TLS의 CA/호스트 검증은 유지한다. HTTPX, libpq도 OpenSSL 의존성을 가지므로
"OpenSSL을 쓰지 않는다"고 판단하지 않는다. worker·TLS 특수 설정·입력 처리 변경 시 재검토한다.

## 기존 잔여 항목

[기존 검토](SECURITY-REVIEW.md)의 제한 조건을 유지한다. 특히 DB libxml2 **Critical 1건은 미수정**이다.
trixie의 `2.12.7+dfsg+really2.9.14-2.1+deb13u3`은 실질 2.9.14 계열이며
CVE-2026-6653의 upstream 수정은 2.11.0, Debian unstable 첫 수정은 2.14.5+dfsg-0.1이다.
SQLite CVE-2026-119373 수정은 upstream3.54.0/unstable3.54.1-1이며 trixie3.46.1은 미수정이다.
homed CVE-2026-16742는 upstream258.10/261.2, unstable261.2-1에서 수정됐으나 trixie257.13은 미수정이다.
배포판 혼합으로 해결하지 않는다. XML/임의 SQL 입력 금지, FTS5/changeset 미사용,
homed/infocmp/TPM 실행 파일 부재·SUID/SGID 제거 조건은 기존 검사로 확인한다.
나머지 기존 패키지의 수정 여부는 전체 Trivy JSON의 FixedVersion과 정확한 설치 버전으로 재검사한다.

## 보완한 검사와 재개 절차

- runtime 검사: C 드라이버·시스템 libpq/crypto 실제 매핑, 번들 부재, gcc/dpkg-source 부재,
  TLS 인증 유지·CRL flag 검토 조건. OpenSSL 버전 변경은 재검토로 중지한다.
- `check_ecr_scan.py`는 **조회/게시 권한이 없는 오프라인 도구**다. 완전한 BASIC JSON에 대해
  registry/repository/**실행 manifest digest** 일치, COMPLETE, pagination 없음, 검사·feed24시간 이내,
  전체 심각도별 개수, exact CVE/package/version/severity를 검증한다. OCI index를 실행 manifest로 혼동하지 않는다.
- 새 항목·버전·심각도·수정 메타데이터·중복/부분 결과·DB 미검토 role·만료를 차단한다.
  ECR BASIC의 fix 필드 부재만으로 미수정이라고 결론내리지 않는다.
- 공식 배포 직전 재검토하도록 만료는 **12월 1일 00:00 KST (11/30 15:00 UTC)**다.
  샘플은 저장된 15개 사실의 비식별 fixture이고 최신 스캔을 대신하지 않는다.
- 사용자 승인 후에만 새 API 후보를 새 불변 태그로 게시하고 재검사한다. 과거 API 태그를 덮어쓰지 않는다.
  DB는 기존 검증 이미지 그대로 게시 대상으로 준비하되 **자체 ECR 결과를 따로 검토**한다.
  새 API에는 libpq 의존 패키지가 추가되므로 기존 API ECR 15건과 같다고 가정하지 않는다.

```sh
# 독립적으로 검증된 게시 영수증의 linux/amd64 digest/registry/repository를 전달한다.
# AWS CLI 전체 자동 페이지 결과를 사용하며 --max-items 결과를 합격 자료로 사용하지 않는다.
python3 deploy/ai-integration/check_ecr_scan.py complete-basic-scan.json \
  --registry APPROVED_REGISTRY --repository APPROVED_REPOSITORY \
  --digest sha256:VERIFIED_EXECUTABLE_MANIFEST --role api
```

통과 출력 `REVIEWED_RESIDUALS_ONLY`는 잔여 취약점 검토 결과이며 게시·운영 승인과 다르다.
병합 후 코드 동일성/후보 digest/CI·리뷰를 확인해 **새 API 및 기존 DB 게시·재검사만** 승인받는다.
GPU 기동·Secret 설치·DB 동기화·STT 활성화·유료 호출은 제외한다. 미검토 검출 시 즉시 중단한다.

## 로컬 검증 (2026-11-27)

- Linux 격리 계약 28개 통과. 실제 API 인증401/401/200·export preview/apply/replay/empty 차단·DB 재시작 보존 통과.
- C 드라이버를 쓰는 격리 PostgreSQL upstream286개 통과. 무네트워크246개 통과/DB 필요40개 skip(앞 검사로 별도 수행).
- Trivy 재검사: 새 API Critical0/High53, 보존 DB Critical1/High72. 새 미검토 튜플·FixedVersion 없음.
  OS 검사 숫자는 같지만 별도 OpenSSL3.5.4 번들은 제거됐다. ECR 새 후보 결과는 아직 없다.
- Spotless·Gitleaks 통과. 빌드 헤더 누락 및 버전 표현 검사식의 초기 실패 로그도 보존했다.

## 공식 근거

- OpenSSL [11/10](https://openssl-library.org/news/secadv/20261110.txt), [11/17](https://openssl-library.org/news/secadv/20261117.txt), [11/24](https://openssl-library.org/news/secadv/20261124.txt)
- Debian [OpenSSL](https://security-tracker.debian.org/tracker/CVE-2026-88823), [dpkg](https://security-tracker.debian.org/tracker/CVE-2026-91094)
- Debian [libxml2](https://security-tracker.debian.org/tracker/CVE-2026-6653), [SQLite](https://security-tracker.debian.org/tracker/CVE-2026-119373), [homed](https://security-tracker.debian.org/tracker/CVE-2026-16742)
- Psycopg [시스템 라이브러리를 쓰는 local installation](https://www.psycopg.org/psycopg3/docs/basic/install.html#local-installation)
