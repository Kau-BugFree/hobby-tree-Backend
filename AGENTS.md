# HobbyTree Backend 작업 지침

이 지침은 `Kau-BugFree/hobby-tree-Backend`에 적용한다. 협업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 기준으로 하고, Issue·PR은 `.github`의 기존 템플릿을 사용한다.

## 작업 시작

- 현재 브랜치와 `git status --short`를 확인하고 기존 커밋·미커밋 변경과 다른 작업 트리를 보존한다. 강제 체크아웃, `reset --hard`, `clean`, `stash`, 이력 재작성과 강제 Push를 사용하지 않는다.
- `git fetch origin develop` 후 최신 SHA를 확인한다. 현재 작업을 전환해야 한다면 최신 `origin/develop`에서 별도 worktree를 만든다.
- 열린 Issue·PR과 브랜치에서 동일 작업, 담당자, 변경 파일과 Flyway 번호의 충돌을 확인한다. 동일한 내 작업은 재사용하고 팀원 브랜치는 직접 변경하지 않는다.
- 팀 API 명세를 우선한다. 확인된 요구사항부터 처리하고, 명세와 구현의 차이 및 미확정 정책은 기록한다.

## GitHub 협업

- Issue → 작업 브랜치 → PR → CodeRabbit·CI 확인 → 사용자 Merge Commit 순서를 따른다. 기존 Issue가 없으면 템플릿에 맞춰 만들고 기존 분류 라벨과 실제 담당자를 사용한다. 버그 수정은 기존 Fix 라벨을 사용한다.
- 브랜치는 `{type}/{실제 Issue 번호}-{영문-kebab-case}` 형식을 쓴다. 기능은 `feature/`로 시작한다. 커밋은 `{type}: {한글 설명}` 형식을 쓰며 PR 제목은 기존 접두어와 한국어 형식을 따른다.
- 작업 브랜치에만 일반 Push한다. PR base는 `develop`으로 두고, Issue 하나의 변경만 포함한다. 템플릿에 `Closes #번호`, 주요 변경, 테스트 결과와 미실행 사유, 리뷰 확인 사항을 적는다. 후속 Issue는 자동 종료 대상으로 연결하지 않는다.
- Push·PR 전에 diff와 Actions 트리거를 검토한다. 인증정보, 접속 설정, 관련 없는 파일과 자동 게시·배포 변경을 제외한다. 요청 범위에서 Push·PR을 진행하고, 검증이나 정책 의존성이 남으면 Draft로 제안한다.
- PR마다 최신 Ruleset·Branch Protection과 Spotless, 관련 Gradle 테스트, 적용되는 Docker·Flyway·Gitleaks, CodeRabbit 및 미해결 리뷰를 확인한다. GitHub 필수 검사와 팀 확인 항목을 구분하고, 접근할 수 없는 항목은 미확인으로 보고한다. 실패는 원인을 확인하고 관련 검사를 다시 실행한다.
- CodeRabbit 지적은 실제 코드에 대조해 필요한 부분을 수정한다. 사용자 작업 기준에서는 CodeRabbit 통과 후 팀원 리뷰를 별도로 요청하지 않는다. `CONTRIBUTING.md`의 팀원 1명 승인 원칙과 GitHub의 실제 승인 요건은 따로 확인하고 차이를 보고한다.
- Codex는 리뷰 대응까지 진행한다. Draft 해제에는 사용자 지시가 필요하다. 승인, Auto-merge와 병합은 사용자가 담당하며 최종 병합 방식은 Merge Commit이다. 저장소 규칙을 우회하지 않는다.

## 구현과 검증

- 기존 `ApiResponse`, 도메인 오류, 공통 예외 처리와 JWT principal을 재사용한다. 인증 사용자 ID로 조회 범위를 제한하고, 활성 회원 전용 API는 서비스에서도 계정 상태를 확인한다.
- 적용된 Flyway SQL과 Hibernate `ddl-auto=validate`를 유지한다. 스키마 변경이 필요하면 최신 develop 및 열린 PR과 번호 충돌을 확인하고 새 마이그레이션으로 분리한다.
- 나무 심기·재개는 5그루 산정 기준을 먼저 확인한다. 활동 완료와 성장은 최초 반영과 반복 완료를 구분하며, 경쟁 요청으로 중복 반영되지 않는지 검증한다.
- 테스트는 허용된 범위에서 격리 PostgreSQL·Redis와 테스트 전용 인증 설정을 사용한다. DB 쓰기 제한이 있으면 테스트에도 적용한다. 운영 설정이나 Secret을 조회하지 않으며 운영 AWS·DB·배포·운영 마이그레이션과 저장소 설정 변경은 별도 범위로 둔다.
- 보고에는 Issue·PR, base/head와 커밋 SHA, 변경 파일, 로컬 테스트·CI·CodeRabbit, 미확인 항목과 병합 조건을 포함한다. 구현, 검증, 병합, 배포 상태를 구분한다.
