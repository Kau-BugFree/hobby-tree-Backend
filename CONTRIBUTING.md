# Contributing Guide

HobbyTree Backend 프로젝트의 일관된 협업 방식과 코드 품질 유지를 위한 기여 가이드입니다.

모든 팀원은 아래 규칙을 기준으로 개발을 진행합니다.

---

## 1. Branch Strategy

프로젝트는 다음 브랜치 전략을 사용합니다.

```text
main
  ↑
develop
  ↑
feature / fix / refactor / chore / docs / deploy
```

### `main`

- 배포 가능한 안정적인 코드를 관리합니다.
- `develop` 브랜치에서 충분히 검증된 변경사항만 병합합니다.
- 직접 Push하지 않고 Pull Request를 통해 병합합니다.

### `develop`

- 개발 중인 기능을 통합하는 브랜치입니다.
- 각 작업 브랜치는 `develop`을 기준으로 생성합니다.
- 직접 Push하지 않고 Pull Request를 통해 병합합니다.

### 작업 브랜치

기능 개발, 버그 수정, 리팩터링 등의 실제 작업을 수행하는 브랜치입니다.

작업을 시작하기 전에 `develop` 브랜치를 최신 상태로 업데이트합니다.

```bash
git switch develop
git pull origin develop
```

이후 Issue 번호를 포함하여 작업 브랜치를 생성합니다.

```bash
git switch -c feature/15-member-signup
```

---

## 2. Branch Naming Convention

브랜치 이름은 다음 형식을 사용합니다.

```text
{type}/{issue-number}-{description}
```

예시:

```text
feature/1-login-api
feature/12-hobby-recommendation
fix/23-login-error
refactor/31-member-service
docs/40-api-document
chore/41-spotless-config
deploy/42-backend-deploy
```

### Branch Type

| Type | 설명 |
| --- | --- |
| `feature` | 새로운 기능 개발 |
| `fix` | 버그 및 오류 수정 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `test` | 테스트 코드 작성 및 수정 |
| `docs` | 문서 작성 및 수정 |
| `chore` | 설정, 의존성, 빌드 등 기타 작업 |
| `deploy` | 배포 및 인프라 관련 작업 |

브랜치 설명은 짧고 명확한 영문 `kebab-case`를 사용합니다.

---

## 3. Commit Convention

커밋 메시지는 다음 형식을 사용합니다.

```text
{type}: {한글 설명}
```

예시:

```text
feat: 로그인 API 구현
fix: 로그인 토큰 검증 오류 수정
refactor: 회원 서비스 로직 분리
test: 로그인 서비스 테스트 추가
docs: README 파일 수정
chore: Swagger 설정 추가
deploy: 백엔드 배포 설정 추가
```

### Commit Type

| Type | 설명 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 및 오류 수정 |
| `refactor` | 기능 변경 없는 코드 리팩터링 |
| `test` | 테스트 코드 추가 및 수정 |
| `docs` | 문서 추가 및 수정 |
| `chore` | 빌드, 설정, 의존성 등 기타 작업 |
| `deploy` | 배포 및 인프라 관련 작업 |

### Commit 작성 원칙

- 커밋 설명은 **한글로 작성**합니다.
- 하나의 커밋에는 가능한 하나의 논리적인 변경사항만 포함합니다.
- 변경 내용을 명확하게 파악할 수 있도록 작성합니다.
- 의미 없는 커밋 메시지는 사용하지 않습니다.

```text
# Bad

수정
수정2
오류 해결
작업 완료

# Good

feat: 취미 추천 API 구현
fix: 로그인 토큰 검증 오류 수정
docs: API 명세 링크 추가
```

---

## 4. Issue Convention

작업을 시작하기 전에 작업 유형에 맞는 GitHub Issue를 생성합니다.

| Issue Type | 용도 |
| --- | --- |
| `[FEAT]` | 새로운 기능 개발 |
| `[FIX]` | 버그 및 오류 수정 |
| `[REFACTOR]` | 기능 변경 없는 코드 구조 개선 |
| `[CHORE]` | 환경 설정, 의존성, 빌드 등 기타 작업 |
| `[DOCS]` | 문서 작성 및 수정 |
| `[DEPLOY]` | 배포 및 인프라 관련 작업 |

각 Issue는 Repository에 등록된 Issue Template을 사용하여 작성합니다.

Issue Template에 따라 다음과 같은 내용을 기록합니다.

- 작업 목적 및 개요
- 구체적인 작업 내용
- 관련 자료
- 필요한 경우 영향 범위 및 참고사항

생성된 **Issue 번호는 작업 브랜치 이름에 포함**합니다.

예를 들어 Issue 번호가 `#15`인 회원가입 기능을 개발하는 경우:

```text
feature/15-member-signup
```

---

## 5. Issue → PR → Merge Workflow

모든 기능 개발 및 주요 변경사항은 다음 흐름을 따릅니다.

```text
Issue 생성
    ↓
develop 최신화
    ↓
작업 브랜치 생성
    ↓
개발
    ↓
Commit & Push
    ↓
Pull Request 생성
    ↓
Code Review
    ↓
Approve
    ↓
Merge Commit
    ↓
Issue 자동 Close
    ↓
작업 브랜치 삭제
```

### 1. Issue 생성

작업 유형에 맞는 Issue Template을 사용하여 Issue를 생성합니다.

### 2. `develop` 최신화

작업을 시작하기 전에 로컬 `develop`을 최신 상태로 업데이트합니다.

```bash
git switch develop
git pull origin develop
```

### 3. 작업 브랜치 생성

Issue 번호를 포함하여 새로운 작업 브랜치를 생성합니다.

```bash
git switch -c feature/15-member-signup
```

### 4. 개발 및 Commit

작업 완료 후 변경사항을 확인하고 Commit합니다.

```bash
git add .
git commit -m "feat: 회원가입 API 구현"
```

### 5. Push

작업 브랜치를 원격 Repository에 Push합니다.

```bash
git push -u origin feature/15-member-signup
```

### 6. Pull Request 생성

일반적인 개발 작업은 다음 방향으로 Pull Request를 생성합니다.

```text
작업 브랜치 → develop
```

릴리즈 시에는 다음 방향으로 Pull Request를 생성합니다.

```text
develop → main
```

PR은 Repository의 Pull Request Template을 사용하여 작성합니다.

관련 Issue는 다음 형식으로 연결합니다.

```text
Closes #15
```

PR이 Merge되면 연결된 Issue가 자동으로 Close됩니다.

---

## 6. Pull Request Convention

Pull Request에는 최소한 다음 내용을 포함합니다.

- 관련 Issue
- 작업 내용
- 주요 변경 사항
- 테스트 및 확인 결과
- 리뷰 요청 사항
- PR Checklist

PR 하나에는 가능한 하나의 Issue 또는 하나의 명확한 작업 범위만 포함합니다.

관련 Issue는 다음 형식을 사용합니다.

```text
Closes #이슈번호
```

예시:

```text
Closes #15
```

API 명세, ERD 또는 프로젝트 공통 구조에 영향을 주는 변경사항은 PR에 명확하게 작성합니다.

---

## 7. Merge Convention

프로젝트의 기본 병합 방식은 **Merge Commit**을 사용합니다.

```text
Create a merge commit
```

다음 방식은 기본적으로 사용하지 않습니다.

```text
Squash and merge
Rebase and merge
```

Merge Commit을 사용하여 작업 브랜치 단위의 개발 이력을 유지합니다.

### Merge 원칙

- 직접 `main`, `develop`에 Push하지 않습니다.
- 모든 변경사항은 Pull Request를 통해 병합합니다.
- 최소 1명의 팀원에게 Code Review 및 Approve를 받은 후 병합합니다.
- 리뷰 중 수정 요청이 있는 경우 수정 후 다시 리뷰를 요청합니다.
- 필요한 CI 검사가 모두 통과한 상태에서 병합합니다.
- Merge가 완료된 작업 브랜치는 삭제합니다.

> GitHub Ruleset을 통해 `main`, `develop` 브랜치의 Pull Request 및 승인 규칙을 강제합니다.

---

## 8. Code Review Convention

Code Review는 오류를 발견하고 팀의 코드 품질과 구현 방식을 일관되게 유지하는 것을 목적으로 합니다.

리뷰어는 다음 내용을 중심으로 확인합니다.

### 기능

- 요구사항과 API 명세에 맞게 구현되었는가?
- 정상적인 요청뿐만 아니라 필요한 예외 상황도 고려했는가?
- 요구사항에 없는 불필요한 기능이 추가되지 않았는가?

### 구조

- Controller, Service, Repository의 책임이 적절하게 분리되어 있는가?
- 기존 프로젝트 구조와 일관성이 있는가?
- 중복 코드가 불필요하게 발생하지 않았는가?
- 클래스와 메서드가 과도한 책임을 가지고 있지 않은가?

### 데이터

- ERD와 Entity 구조가 일치하는가?
- Entity를 API 응답으로 직접 노출하지 않는가?
- 데이터 구조 변경이 다른 기능에 미치는 영향을 고려했는가?

### 공통 코드

- 기존 `ApiResponse` 형식을 사용하고 있는가?
- 기존 공통 예외 처리 구조를 사용하고 있는가?
- 이미 존재하는 공통 기능을 중복 구현하지 않았는가?
- 공통 구조 변경이 필요한 경우 팀원과 충분히 논의했는가?

### 리뷰 의견

리뷰 의견은 가능한 한 **문제점과 이유를 함께 작성**합니다.

```text
# Bad

이렇게 하면 안 될 것 같습니다.

# Good

Controller에서 비즈니스 로직을 직접 처리하고 있어
Service 계층으로 분리하는 것이 좋을 것 같습니다.
```

단순한 질문이나 반드시 수정할 필요가 없는 제안은 해당 의도가 드러나도록 작성합니다.

---

## 9. Code Style

Java 코드 스타일은 수동으로만 관리하지 않고 **Spotless**를 사용하여 일관성을 유지합니다.

코드 스타일은 프로젝트의 `build.gradle`에 정의된 Spotless 설정을 기준으로 합니다.

### 코드 스타일 검사

작업 완료 후 PR을 생성하기 전에 다음 명령어로 코드 스타일을 검사합니다.

Linux / macOS:

```bash
./gradlew spotlessCheck
```

Windows PowerShell:

```powershell
.\gradlew spotlessCheck
```

스타일 위반이 있는 경우 다음 명령어를 사용하여 자동으로 수정합니다.

Linux / macOS:

```bash
./gradlew spotlessApply
```

Windows PowerShell:

```powershell
.\gradlew spotlessApply
```

자동 수정 후 변경사항을 확인하고 다시 `spotlessCheck`를 실행합니다.

### Code Style 원칙

- Spotless에서 관리할 수 있는 포맷은 수동으로 맞추지 않습니다.
- IDE별 개인 포맷보다 프로젝트의 Spotless 설정을 우선합니다.
- 사용하지 않는 import를 남기지 않습니다.
- 의미를 파악하기 어려운 축약어 사용을 지양합니다.
- 클래스와 메서드의 책임이 지나치게 커지지 않도록 작성합니다.

---

## 10. Pull Request 전 Checklist

PR 생성 전 다음 사항을 확인합니다.

- [ ] 최신 `develop`을 기준으로 작업했는가?
- [ ] 작업 브랜치 이름에 Issue 번호가 포함되어 있는가?
- [ ] Issue 범위에 해당하는 작업만 포함되어 있는가?
- [ ] 프로젝트가 정상적으로 빌드되는가?
- [ ] 필요한 테스트를 수행했는가?
- [ ] `spotlessCheck`를 통과하는가?
- [ ] API 명세 및 ERD가 필요한 경우 구현 내용과 일치하는가?
- [ ] 기존 공통 코드 및 프로젝트 구조를 준수했는가?
- [ ] 불필요한 로그, 테스트 코드 또는 주석이 남아 있지 않은가?
- [ ] 비밀번호, API Key 등의 민감정보가 포함되지 않았는가?
- [ ] 관련 Issue를 `Closes #이슈번호` 형식으로 연결했는가?
- [ ] 새로운 의존성을 추가한 경우 필요성을 공유했는가?

---

## 11. 기본 협업 원칙

- API 명세와 ERD를 기준으로 구현합니다.
- 공통 구조를 변경해야 하는 경우 팀원과 먼저 논의합니다.
- 새로운 라이브러리나 의존성을 추가할 경우 필요성을 공유합니다.
- 다른 기능에 영향을 줄 수 있는 변경사항은 PR에 명확하게 작성합니다.
- 인증, 데이터베이스 구조 등 프로젝트 전반에 영향을 주는 변경은 단독으로 결정하지 않습니다.
- Repository의 Issue 및 Pull Request Template을 사용합니다.
- `main`, `develop` 브랜치에 직접 Push하지 않습니다.
- GitHub Ruleset 및 CI 검사 결과를 준수합니다.