# CI BuildKit DNS (Issue #171)

## 확인한 원인

2026-11-11 GitHub hosted Ubuntu runner의 기본 resolver 경로에서
`archive.ubuntu.com`, `security.ubuntu.com` 및 Ubuntu 공식 미러 이름 조회가
시간 초과됐다. 같은 runner에서 `github.com`은 조회됐고, `1.1.1.1`과
`8.8.8.8`에 직접 질의한 Ubuntu A 레코드는 모두 `NOERROR`로 응답했다.
테스트 실행 전에도 같았으므로 테스트가 DNS를 변경한 현상은 아니다.
상위 resolver 내부의 장애 원인까지 확인한 것은 아니다.

진단 기록: [Actions run 43043258729](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/43043258729).
동일한 고정 JRE 이미지의 로컬 BuildKit root/`_apt` DNS와 APT update는 성공했다.

## 수정 범위

`docker-validation.yml`의 두 Buildx worker만 `buildkitd-config-inline`으로
공개 resolver를 명시한다. 고정된 과거 V5 후보를 체크아웃하는 수동 job에서도
새 설정 파일의 존재 여부에 의존하지 않는다. Docker가 제공하는
[BuildKit DNS 설정](https://docs.docker.com/build/buildkit/toml-configuration/)을
사용하며 runner 전체의 resolver, Docker daemon 또는 운영 네트워크는 변경하지 않는다.
공개 의존성을 다운로드하는 CI build의 DNS 질의가 이 resolver들로 전달된다.
사설 의존성이 필요한 환경에 이 설정을 그대로 사용하지 않는다.

APT 저장소/서명 검증, `Acquire::Retries=0`, 목록 다운로드 오류 시 실패,
최소 보안 패키지 버전 조건을 유지한다. Dockerfile과 운영 이미지 설정은 그대로다.
검증용 workflow는 `push: false`이며 ECR 게시/운영 배포 workflow는 변경하지 않는다.
임시 원인 분석 단계는 제거했다. 이후 동일 증상은 이 기록과 BuildKit 빌드 로그를 비교한다.

## 완료 기준

DNS 우회 설정만으로 성공이라고 판단하지 않는다. 전체 Docker job에서 이미지 빌드,
TLS PostgreSQL 기동, 설정 오류 거절, 인증 및 Redis 장애/복구,
격리 V18→V24 마이그레이션·권한 검사가 모두 성공해야 한다.
Spotless, 별도 Flyway job과 CodeRabbit도 확인한다.
이 CI 성공은 실패한 운영 전환의 복구 적용을 허가하지 않는다.

## 2026-11-19 APT HTTP 연결 실패 후속 (Issue #252)

위 DNS 조치 이후 [develop e4cd3ab의 CI](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/44519665222)는
두 실행 모두 Ubuntu 저장소의 IP를 해석했으나 HTTP(80) 연결에 실패했다.
이는 앞서 기록한 이름 해석 실패와 구분하며, GitHub/Ubuntu 네트워크 내부 원인을 단정하지 않는다.

고정 JRE에는 시스템 CA와 APT HTTPS 전송기가 이미 있다. Dockerfile의 runtime 패치 단계에서
CA 파일을 먼저 확인하고 amd64 archive/security 저장소를 `https://azure.archive.ubuntu.com/ubuntu`로 바꾼다.
이는 [Microsoft의 Ubuntu 이미지 구성 안내](https://learn.microsoft.com/en-us/azure/virtual-machines/linux/create-upload-ubuntu)에
포함된 Ubuntu 미러다. 기본 archive/security 호스트는 로컬에서도 HTTP·HTTPS가 시간 초과됐고,
Azure 미러는 HTTPS 응답을 확인했다. ports 저장소는 기존 호스트의 HTTPS로 전환하며 이번 빌드 검증 대상은 linux/amd64다.
suite/component·공식 서명 키·고정 base digest는 유지한다. CA가 없거나 인증/서명 검증,
패키지 갱신·설치 또는 최소 보안 버전 검사가 실패하면 빌드는 계속 실패한다.

`Acquire::Retries=0`, `--error-on=any`, 기존 네 패키지 버전 하한과 non-root·healthcheck를 유지한다.
재시도 조건은 [Ubuntu의 CVE 설명](https://ubuntu.com/security/CVE-2026-109518)에 따라 완화하지 않는다.
TLS는 [APT HTTPS의 기본 인증서·호스트 검증](https://manpages.ubuntu.com/manpages/focal/man1/apt-transport-https.1.html)을
사용하며 검증 해제 옵션이나 비공식 미러·HTTP fallback을 추가하지 않는다.

격리 검증은 HTTP가 거절되는 네트워크에서 이전 APT 경로의 실패와 새 runtime 명령의 성공을 비교하고,
신뢰하지 않는 CA·서명 없는 저장소의 거절을 확인한다. 실제 linux/amd64 이미지와 기존 격리
PostgreSQL·Redis CI도 검증한다. 위의 기존 DNS resolver 설정과 워크플로는 수정하지 않는다.
ECR 게시·운영 적용·운영 네트워크 변경은 이 작업의 범위가 아니다.
