# 준비물 API·전용 DB 이미지 보안 검토 — Issue #305

검토일: 2026-11-27 KST (UTC 2026-11-26). 범위: AI `0920a3ada3b50700af61bf792c859c3606a4a1be`
준비물 모듈의 배포 이미지. 코치/STT 이미지·운영 GPU·BE RDS는 변경하지 않는다.

> 후속 ECR 추가 검출·드라이버 수정과 새 승인 기준은 [Issue #307 검토](ECR-SECURITY-REVIEW.md)를 따른다. 아래 수치는 #306 후보의 이력이다.

## 변경과 확인 기준

- Python **3.11**, PostgreSQL **16** 유지. 기반 OS를 digest 고정 Debian 13으로 변경하고
  공식 서명 저장소의 보안 업데이트를 적용한다. Debian 12/13 저장소 혼용이나 서명 검증 해제 없음.
- API는 설치에만 쓰는 setuptools/wheel/pip를 production stage에서 제거한다.
  pytest stage는 production과 분리하며 게시 대상이 아니다.
- DB gosu 1.20의 공식 소스 commit `29b939aa5bd4dc588af765b8248f6e39150d2565`와 tar SHA-256을 고정,
  Go 1.27.3으로 다시 빌드한다. upstream 소스 변경 없이 UID999 전환을 실제 검사한다.
- 두 이미지의 SUID/SGID 비트를 제거하고 사용하지 않는 `infocmp` 실행 파일을 제거한다.
- APT 인덱스 갱신 실패를 `APT::Update::Error-Mode=any`로 실패 처리한다.
  초기 DB 시도에서는 HTTPS 인증서 검증 실패가 경고로 끝나 stale index가 사용됐다.
  DB는 공식 이미지의 원래 APT 전송 설정을 유지하며 **InRelease 서명·패키지 hash 검증**을 유지한다.
  실패 로그/이전 후보는 로컬 증적으로 보존한다. 실제 키·인증값은 APT 요청에 없다.
- OS/glibc/locale 변경이므로 **새 준비물 전용 볼륨에만 설치**한다.
  기존 PostgreSQL 데이터 볼륨을 단순 교체하지 않는다. 별도 백업·collation/reindex 검토가 필요하다.

## 검사 결과의 의미

Trivy 0.76.2, HIGH/CRITICAL, ignorefile 없음, OS·언어 패키지 전체 검사.
숫자는 패키지×CVE 검출 수이며 실제 원격 악용 건수가 아니다. 언어별 빈 결과는 제외한다.

| 후보 | 변경 전 Critical / High | 변경 후 Critical / High | 수정 버전 제공 항목 |
|---|---:|---:|---:|
| 준비물 API | 2 / 102 | 0 / 53 | 0 |
| 준비물 DB | 4 / 141 | 1 / 72 | 0 |

API의 setuptools vendored `jaraco.context` CVE-2026-23949와 `wheel` CVE-2026-24049는
해당 배포 패키지 제거로 해소한다. Go 1.24.6 gosu의 26개 stdlib 검출은 재빌드 후 사라졌다.
SQLite CVE-2025-7458, zlib CVE-2023-45853 등 이전 Critical은 새 OS 패키지에서 해소됐다.
DB zstd는 `1.5.7+dfsg-1+deb13u1` 이상이며 런타임 검사로도 강제한다.
아래 잔여 항목을 숨기거나 전부 수정했다고 처리하지 않는다.

## 잔여 항목과 도달 경로 검토

각 항목의 정확한 이미지 역할·패키지·설치 버전·심각도는 [security-review.json](security-review.json)에 있다.
현재 stable 배포판에서 수정 버전이 제공되지 않는 항목만 남겼다.
아래 판단은 고정 AI 소스와 private Compose에 한정한 **조건부 영향 분석**이다.

| 항목 | 영향과 현재 제한 |
|---|---|
| libxml2: CVE-2026-6653(Critical), 74860, 86138, 86139, 86140, 86142, 86143, 86144 | PostgreSQL XML 기능이 라이브러리를 사용할 수 있으므로 제거하지 않는다. API·importer는 고정 SQL/JSON과 바인딩 값을 사용하며 XML 파싱 API나 임의 SQL을 노출하지 않는다. DB는 전용 내부망·포트 미공개, app NOSUPERUSER. DB 자격증명 탈취/SQL 주입이 생기면 이 제한은 충분하지 않다. XML 입력·사용자 SQL 추가 시 재검토. **미수정 Critical 1건이므로 운영 허용은 별도 판단.** |
| SQLite: CVE-2026-119373, 119374, 119379 | FTS5 손상 색인·공격자 SQL·session changeset 경로. 배포 catalog는 PostgreSQL 강제, worker는 비활성. 모듈 import에 sqlite3가 필요해 라이브러리는 유지. provider-state의 고정 SQL에는 FTS5/changeset 적용이 없고 외부 SQLite 파일을 받지 않는다. worker 활성화/DB 파일 수신 추가 시 재검토. |
| util-linux: CVE-2026-76642, 78408, 78409, 78410 | mount/nsenter의 권한·namespace 조건. SUID/SGID 제거, API cap_drop ALL/no-new-privileges, host namespace/mount 권한 제공 금지. DB는 bootstrap 후 gosu postgres로 전환. 호스트 관리자가 컨테이너 안에서 특권 mount/nsenter를 실행하는 용도로 사용하지 않는다. |
| OpenSSL: CVE-2026-107494, 124709 | QUIC 연결/stream 메모리 고갈. Uvicorn HTTP/TCP 및 PostgreSQL TCP를 사용하고 QUIC/HTTP3를 제공하지 않는다. 외부 worker도 비활성. QUIC 추가 시 재검토. |
| libacl1: CVE-2026-54369 | 공격자가 경로를 교체할 수 있는 특권 ACL 파일 작업. 이미지에 SUID/SGID 없음, DB bootstrap은 운영자가 준비한 전용 디렉터리만 사용. 신뢰하지 않는 공유 볼륨·파일을 root로 처리하지 않는다. |
| systemd 계열: CVE-2026-16742 | systemd-homed 사용자 그룹 관리. 컨테이너에서 homed를 사용하지 않으며 바이너리 부재를 검사한다. 라이브러리의 source-package 검출은 남는다. |
| ncurses 계열: CVE-2025-69720 | infocmp 분석 경로. infocmp 제거 및 부재 검사. Python/psql에 필요한 라이브러리·터미널 데이터는 유지하므로 패키지 검출은 남는다. |
| Perl 계열: CVE-2026-9538 | Archive::Tar에 공격자 tar 헤더를 전달하는 경로. API는 Python/JSON, DB는 PostgreSQL 고정 SQL이며 사용자 tar를 Perl로 처리하지 않는다. 압축 업로드 처리 추가 시 재검토. |
| GnuPG 계열: CVE-2026-24882 | TPM용 tpm2daemon PKDECRYPT 경로. TPM 장치/키를 주입하지 않으며 tpm2daemon 부재 검사. DB의 패키지 설치 지원 의존성 검출은 남는다. |

## 자동 검증과 차단

1. 실제 최종 production 이미지에서 UID, OS, SQLite/zstd baseline, gosu 버전·권한 전환,
   빌드 도구/infocmp/homed/tpm2daemon 부재, SUID/SGID 부재를 네트워크 없이 검사한다.
2. 합성 Secret·고유 임시 Docker 볼륨/네트워크로 초기화, API 인증 401/401/200,
   import preview/apply/replay/empty 차단, DB 재시작 보존, upstream PostgreSQL 회귀를 실행한다.
3. CI의 Trivy 전체 JSON을 artifact로 남긴다. 검사 실패를 성공으로 바꾸는 `|| true`/ignorefile 없음.
4. `check_vulnerabilities.py`는 스캔 이미지 ID/OS/구조와 정확한 검토 튜플을 대조한다.
   **새 CVE, 패키지/설치 버전/심각도 변경, 수정 버전 등장, 검토 만료**는 실패한다.
   검출이 사라지는 것은 허용한다. 검토 만료는 UTC **2026-12-04**이며 이후 근거를 다시 확인한다.
5. CI 통과 출력은 `REVIEWED_RESIDUALS_ONLY`, `deploymentApproved:false`다.
   취약점 0건, ECR 검사 통과, GPU 운영 승인으로 해석하지 않는다.

## 다음 승인 범위

병합본 일치·최종 image digest·CI/리뷰 통과 후 **이미지 게시만** 별도 승인받는다.
필요 시 준비물 API/DB 전용 ECR 두 저장소(AES256·태그 불변)를 생성하고 검증된 production
두 이미지만 게시·재검사한다. ECR 분류/신규 항목이 이 검토와 다르면 중지한다.
STT 기존 이미지는 별도 검토 결과를 유지한다. 서버 기동/Secret 설치/데이터 동기화/운영 전환/
상품 수집/STT·Haiku 유료 호출은 이 승인에 포함하지 않는다.
현재 미수정 Critical의 제한 조건 수용, GPU 메모리 공존, 상품 저장·보관 정책,
export 담당·전달 방식, Google STT 권한·결제는 운영 단계 전에 별도 확정해야 한다.

## 근거 (2026-11-27 KST 조회)

- [Debian SQLite Critical](https://security-tracker.debian.org/tracker/CVE-2025-7458), [zlib](https://security-tracker.debian.org/tracker/CVE-2023-45853), [zstd 수정](https://security-tracker.debian.org/tracker/CVE-2026-124851)
- [gosu upstream 1.20](https://github.com/tianon/gosu/releases/tag/1.20), [upstream 보안 설명](https://github.com/tianon/gosu/blob/1.20/SECURITY.md)
- [libxml2 Critical](https://security-tracker.debian.org/tracker/CVE-2026-6653), [libxml2 URI](https://security-tracker.debian.org/tracker/CVE-2026-86139)
- [SQLite FTS5](https://security-tracker.debian.org/tracker/CVE-2026-119373), [secure-delete](https://security-tracker.debian.org/tracker/CVE-2026-119374), [changeset](https://security-tracker.debian.org/tracker/CVE-2026-119379)
- [util-linux mount](https://security-tracker.debian.org/tracker/CVE-2026-76642), [nsenter](https://security-tracker.debian.org/tracker/CVE-2026-78408), [subdir](https://security-tracker.debian.org/tracker/CVE-2026-78409), [bind mount](https://security-tracker.debian.org/tracker/CVE-2026-78410)
- [OpenSSL QUIC 1](https://security-tracker.debian.org/tracker/CVE-2026-107494), [QUIC 2](https://security-tracker.debian.org/tracker/CVE-2026-124709)
- [ACL](https://security-tracker.debian.org/tracker/CVE-2026-54369), [homed](https://security-tracker.debian.org/tracker/CVE-2026-16742), [infocmp](https://security-tracker.debian.org/tracker/CVE-2025-69720), [Perl Tar](https://security-tracker.debian.org/tracker/CVE-2026-9538), [GnuPG TPM](https://security-tracker.debian.org/tracker/CVE-2026-24882)
