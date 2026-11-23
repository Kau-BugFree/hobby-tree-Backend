# 소형 GPU 이미지 High 영향 검토

Issue #256, 2026-11-22. 대상은 `coach.Dockerfile`로 빌드한 소형 GPU 후보와
`compose.candidate.yml`, 고정 AI9364bfa다. 기존 CPU 운영 이미지·EC2 host OS의
보안 판정을 대신하지 않는다. 실제 GPU·운영 전환 성공 결과도 아니다.

## 수정 및 검사

- zlib1g를 Debian 수정 버전 `1:1.3.dfsg+really1.3.1-1+deb13u1`로 고정 설치한다.
  HTTPS APT 및 서명 검증을 사용하며 다른 배포판 패키지를 섞지 않는다.
- 불필요한 `/usr/bin/infocmp`를 제거하고 이미지 실행 파일의 setuid/setgid를 제거한다.
  dpkg 패키지 DB를 지우거나 스캐너 ignore를 추가하지 않는다.
- `check_image_security.py`는 실제 설치 버전·시스템 libz 로드·압축 왕복,
  UID10001·capability0·no-new-privileges, 위험 도구/모듈 부재와 고정 AI의
  SQLite schema·prefix 검색·삭제·integrity를 검사한다. DB는 `/tmp`의 폐기 fixture뿐이다.
- zlib 런타임 문자열1.3.1만으로 패치 여부를 판단하지 않는다. Debian 패키지 버전과
  실제 시스템 라이브러리 로드를 함께 확인한다. AI Python/torch/model fingerprint는 유지한다.

## 결과 해석과 적용 전제

수정 전 Trivy0.76.2 High13종/패키지 매칭51건, Critical0건이다.
수정 후 동일 스캐너의 High12종/패키지 매칭50건, Critical0건이며 zlib 결과는0건이다.
수정 전후 Python 패키지에서 High/Critical 결과는 없었다. 아래 조건부 영향 검토는
취약한 패키지가 수정됐다는 뜻이나 향후 발견될 취약점에 대한 포괄 예외가 아니다.
새 CVE·고정 AI 원본/의존성·schema·런타임 권한·입력 경로 변경 시 재검토한다.

| CVE | 실제 조건과 후보 영향 | 조치·유지 조건 |
|---|---|---|
| [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | zlib gz* 쓰기 오류 후 버퍼 처리 결함. 라이브러리를 실제 사용하므로 패치한다. | 수정 패키지 설치·로드·압축 테스트. |
| [CVE-2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642) | 특권 mount helper 실패 후 post-hook 실행. 후보 앱은 mount helper를 호출하지 않는다. | setuid 제거·cap_drop ALL·no-new-privileges·read_only 유지. privileged/host mount 권한 금지. |
| [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) | root가 nsenter --join-cgroup으로 공격자 namespace에 진입할 때 FD 권한이 상속된다. | 후보 내부 root/nsenter 운영 금지, host PID/cgroup namespace 및 Docker socket 공유 금지. 앱 capability0 확인. |
| [CVE-2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409) | fstab에서 허용한 X-mount.subdir와 중간 symlink가 필요하다. | 앱의 mount/fstab 수정 경로 없음. setuid 제거와 capability0를 확인한다. host driver 보안과 별도로 판단한다. |
| [CVE-2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410) | 특권 restricted bind mount에서 공격자가 경로를 바꿀 때 ACL/owner가 다른 대상에 적용된다. | 앱이 mount를 수행하지 않으며 setuid/capability/새 권한 획득을 차단한다. |
| [CVE-2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369) | 특권 libacl pathname 호출과 공격자 제어 symlink가 필요하다. | 앱 코드에 ACL 호출 없음·non-root·권한 상승 차단. root 관리 작업의 안전까지 보장하지 않는다. |
| [CVE-2025-69720](https://security-tracker.debian.org/tracker/CVE-2025-69720) | ncurses의 infocmp CLI 문자열 분석 경로. libncurses/tinfo의 존재만으로 앱 경로를 의미하지 않는다. | 해당 CLI 파일 제거·부재 검사. OS 패키지 매칭은 남을 수 있다. |
| [CVE-2026-119373](https://security-tracker.debian.org/tracker/CVE-2026-119373) | SQLite detail=none FTS5에 음수 rowid를 넣고 prefix MATCH할 때의 결함. | 원본 `retrieval.initialize_database`는 detail 기본값(full), records AUTOINCREMENT의 rowid를 FTS에 넣는다. 실제 schema/정상 prefix fixture 검사. 사용자 SQL/schema/DB 업로드 금지. |
| [CVE-2026-119374](https://security-tracker.debian.org/tracker/CVE-2026-119374) | 손상된 FTS5 DB와 FTS secure-delete 활성화 후 삭제가 필요하다. | FTS secure-delete 미설정 확인. GPU 시작 전 원본 DB 포함34자산 해시 검사. B에서는 새 전용 상태 경로에 검증된 색인만 복사하고 SQLite sidecar(-wal/-shm/-journal)가 없는지 확인한다. 검증 후 외부 프로세스가 DB를 수정하지 못하도록 UID10001 전용 접근을 유지한다. |
| [CVE-2026-119379](https://security-tracker.debian.org/tracker/CVE-2026-119379) | SQLite session extension의 crafted changeset 적용 경로. | 고정 AI 코드에서 changeset 적용 없음, Python sqlite3 API 사용. 동적 extension/외부 SQL·DB·changeset 입력 없음. 라이브러리의 기능 존재와 앱 노출을 구분한다. |
| [CVE-2026-124709](https://security-tracker.debian.org/tracker/CVE-2026-124709) | OpenSSL QUIC 서버의 스트림 거절/리셋으로 메모리 누적. | 현재 Uvicorn TCP HTTP, 외부 호출도 HTTPS TCP이며 UDP/QUIC listener 없음. 후보 SG는 BE에서 TCP8000/8003만 허용. QUIC 도입 시 재검토. |
| [CVE-2026-16742](https://security-tracker.debian.org/tracker/CVE-2026-16742) | systemd-homed가 관리하는 로그인 사용자의 그룹 변경 경로. | 이미지에 homed 실행 파일 없음을 확인한다. libsystemd/libudev의 패키지 매칭과 실제 daemon 존재를 구분한다. |
| [CVE-2026-9538](https://security-tracker.debian.org/tracker/CVE-2026-9538) | Perl Archive::Tar의 공격자 tar 헤더 처리. | slim 이미지의 perl-base에 해당 Tar.pm 없음을 검사. 앱에서 Perl·사용자 archive 추출을 사용하지 않는다. |

## 2026-11-23 ECR Basic 추가 검토 (Issue #258)

서울 ECR의 이전 linux/amd64 manifest `sha256:a82986a245bc07686355be1f29ba125cafc4017367e9fa45e8d4796fc4dd66a2`에서
High 10건 중 위에서 검토한 QUIC 서버 1건을 제외한 9건이다. 아래 링크는 Debian trixie 기준이다.

| CVE | 소스 패키지 | 영향을 받는 기능 | B 시험 경로의 판정 |
|---|---|---|---|
| [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) | glibc | 통화 표시용 strfmon 계열 | 검색·점수 계산에 통화 포맷 호출이 없다. 프로세스가 glibc를 쓰므로 패치한다. |
| [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435) | glibc | 레거시 DNS 레코드 출력 함수의 TSIG 처리 | 일반 이름 해석과 별개다. 코치가 DNS 레코드를 출력하는 경로는 발견되지 않았다. 패치한다. |
| [CVE-2026-121482](https://security-tracker.debian.org/tracker/CVE-2026-121482) | OpenSSL | CRL 검사를 켠 CMS/PKCS#7 서명 검증 | 시험 입력은 검색 문자열이다. 서명된 메시지나 CRL을 검증하는 단계가 없다. |
| [CVE-2026-107496](https://security-tracker.debian.org/tracker/CVE-2026-107496) | OpenSSL | 수신인의 EC 인증서로 CMS 암호화 | 수신인 인증서를 받거나 검색 결과를 CMS로 암호화하지 않는다. |
| [CVE-2026-88823](https://security-tracker.debian.org/tracker/CVE-2026-88823) | OpenSSL | AES-GCM-SIV의 분할 AAD 처리 | 모델 추론과 SQLite 검색은 AAD를 받거나 해당 암호를 선택하지 않는다. |
| [CVE-2026-95692](https://security-tracker.debian.org/tracker/CVE-2026-95692) | OpenSSL | AES-GCM-SIV의 비표준 길이 IV | 검색 요청에는 키·IV 입력이 없고 해당 암호 초기화 호출을 발견하지 못했다. |
| [CVE-2026-95691](https://security-tracker.debian.org/tracker/CVE-2026-95691) | OpenSSL | QUIC 클라이언트의 TLS 처리 | 검증기는 loopback HTTP/TCP를 사용하며 코치에서 QUIC 클라이언트를 열지 않는다. |
| [CVE-2026-84786](https://security-tracker.debian.org/tracker/CVE-2026-84786) | OpenSSL | DANE를 켠 TLS 클라이언트의 TLSA 처리 | 로컬 검색은 DANE 검증이나 TLSA 조회를 요청하지 않는다. |
| [CVE-2026-107493](https://security-tracker.debian.org/tracker/CVE-2026-107493) | OpenSSL | 기존 X509_INFO 스택을 재사용하는 PEM 읽기 | 코치 검색 API는 PEM 입력을 받지 않는다. 시스템 CA를 쓰는 표준 TLS 경로와 별개의 API 조건이다. |

- glibc 두 건 모두 Debian 수정판은 `2.41-12+deb13u5`다. 이미지에 설치된 libc6와 libc-bin을 u4에서 함께 갱신한다.
- OpenSSL 7건 모두 trixie 수정판이 확인되지 않았다. openssl·libssl3t64·openssl-provider-legacy를
  `3.5.7-1~deb13u3`에서 `3.5.9-1~deb13u1`로 갱신해도 이 7건은 미수정으로 취급한다.
  배포판에 수정판이 나오면 다시 빌드한다.
- 검토 대상은 고정 AI `9364bfa`의 `app/retrieval.py`·모델 코드, GPU launcher와
  `gpu_runtime.py`의 `/gpu/search-check`, `verify_trial.py`가 사용하는 시험 요청이다.
  Compose에서 외부 LLM을 끄고 고정된 로컬 자산을 사용한다. OS 패키지 매칭은 계속 기록하며,
  종속된 네이티브 라이브러리 전체나 운영 코치의 모든 입력에 무영향이라고 판단하지 않는다.
- 재검사 시 새 linux/amd64 digest를 확인한다. glibc 두 건의 소멸과 Critical 0건을 확인하고
  모든 잔여 High를 CVE·소스 패키지·설치 버전 및 실제 시험 조건과 대조한다.
  추가·변경된 영향이 있으면 GPU를 기동하기 전에 검토한다. 운영 TLS, 업로드 또는 외부 호출을 열 때는 별도 평가한다.

## B 시험과 C 운영의 경계

B는 검토한 이미지와 Compose, 폐기 가능한 전용 상태, 검증된 입력 자료, 서비스 토큰을
가진 내부 시험 요청만 허용한다. LLM 비활성·유료 호출0·운영 DB/세션 미사용 조건이다.
이미지 패치/CI/Codex 리뷰 및 실제 quota 승인·사용자 B 승인 후에만 서버를 만든다.
새 GPU의 kernel/GRID/runtime 설치·재부팅·보안 설정은 별도로 실측한다.

C 전환은 실사용 입력·최신 세션·host 패치 상태·운영 이미지 재스캔과 예외 전제의
유효성을 다시 검토한 뒤 승인받는다. 이 문서는 기존 High를 자동 허용하거나
CI에서 숨기는 설정이 아니다. Debian 수정판이 나오면 해당 패키지 갱신을 우선한다.

검사 실행 예:

```sh
docker run --rm --network none --read-only --cap-drop ALL \
  --security-opt no-new-privileges --tmpfs /tmp --entrypoint python \
  hobbytree/coach-gpu:review /service/check_image_security.py
```
