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

`docker-validation.yml`의 두 Buildx worker만 `.github/buildkitd-ci.toml`을
사용해 공개 resolver를 명시한다. Docker가 제공하는
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
