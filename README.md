# 🌳 HobbyTree Backend

> 취미를 시작하고 지속하게 돕는 개인 맞춤형 취미 온보딩 성장 플랫폼

HobbyTree는 사용자의 취향과 생활 조건에 맞는 취미와 작은 첫 체험을 제안하고,  
실제 활동에서 얻은 만족도와 체감 난이도를 바탕으로 다음 활동을 조정하는 서비스입니다.

단순히 취미를 추천하는 것에서 끝나지 않고,

**취미 탐색 → 첫 체험 → 활동 기록 → 피드백 → 다음 활동**

으로 이어지는 경험을 제공하는 것을 목표로 합니다.

---

## 📌 Project Overview

| 항목 | 내용 |
| --- | --- |
| 프로젝트명 | HobbyTree |
| 팀명 | 버그 프리 |
| 프로젝트 주제 | 개인 맞춤형 취미 온보딩 성장 플랫폼 |
| 플랫폼 | Mobile Application |
| 개발 기간 | 11주 (개발 8주 + 파일럿 3주) |
| Backend | Java 21, Spring Boot |
| Database | PostgreSQL |
| Infrastructure | AWS |

---

## 🎯 Background

취미를 시작하려는 사용자는 다음과 같은 어려움을 겪을 수 있습니다.

- 자신의 조건에 맞는 취미를 선택하기 어려움
- 취미를 시작해도 다음에 무엇을 해야 할지 알기 어려움
- 취미 활동을 꾸준히 이어가기 어려움
- 함께 활동하고 싶을 때 적절한 동반자를 찾기 어려움

HobbyTree는 사용자의 **예산, 시간, 선호 조건** 등을 고려해 시작하기 쉬운 활동을 제안하고,
활동 이후의 기록과 피드백을 활용하여 취미를 지속할 수 있도록 돕습니다.

---

## ✨ Core Features

### 🎯 개인 맞춤형 취미 추천

사용자의 취향과 생활 조건을 기반으로 적합한 취미와 활동을 추천합니다.

- 예산
- 사용 가능한 시간
- 준비 조건
- 활동 선호

### 🌱 작은 첫 체험

취미를 처음부터 크게 시작하는 대신 부담 없이 시도할 수 있는 작은 활동을 제공합니다.

- 준비물
- 예상 시간
- 예상 비용
- 추천 이유

### 📝 활동 기록 및 피드백

첫 체험 이후 사용자의 경험을 기록합니다.

- 만족도
- 체감 난이도
- 활동 결과

기록된 피드백을 기반으로 이후 활동의 난이도와 방식을 조정합니다.

### 🔄 취미 전환

현재 취미가 맞지 않는 경우 다른 취미를 다시 탐색할 수 있습니다.

```text
취미 추천
   ↓
첫 체험
   ↓
활동 기록
   ↓
피드백
   ↓
다음 활동
   ↓
지속 또는 다른 취미 탐색
```

### 👥 동반자 연결

함께하는 활동이 필요한 경우 사용자가 선택적으로 동반자 연결 기능을 사용할 수 있습니다.

동반자 연결은 HobbyTree의 핵심 기능을 이용하기 위한 필수 조건이 아닌 보조 기능입니다.

> MVP에서는 취미 추천, 첫 체험, 기록 및 취미 전환 흐름을 우선적으로 구현합니다.

---

## 🛠 Tech Stack

### Backend

- Java 21
- Spring Boot 4
- Spring Security
- Spring Data JPA
- JWT
- Gradle

### Database

- PostgreSQL

### API Documentation

- Swagger / OpenAPI

### Infrastructure

- AWS EC2
- AWS RDS
- AWS S3
- Docker
- GitHub Actions
- CloudWatch

### Code Quality

- Spotless
- Google Java Format

---

## 🏗 System Architecture

```text
TBD
```

Backend는 사용자 인증 및 권한 관리, 서비스 데이터 관리와 API 제공을 담당하며,
AI 서비스와 연동하여 사용자 조건에 맞는 취미 및 활동 추천 결과를 제공합니다.

---

## 📂 Backend Responsibilities

Backend에서는 다음 기능을 담당합니다.

- 사용자 인증 및 권한 관리
- 개인 데이터 접근 제어
- 취미 및 활동 카드 관리
- AI 추천 서비스 연동
- 추천 이유 및 버전 관리
- 체험 및 피드백 관리
- 취미 전환 API
- 데이터베이스 관리
- 파일 저장 관리
- 배포 및 운영 환경 관리

---

## 👥 Team

| 이름 | 역할 |
| --- | --- |
| 김현진 | Team Leader / AI |
| 김우빈 | PM / Backend |
| 윤여빈 | Backend |
| 이승준 | AI |
| 송우정 | Frontend |
| 김민수 | Frontend |

---

## 🌿 Branch Strategy

```text
main
  ↑
develop
  ↑
feature / fix / refactor / chore / docs / deploy
```

작업 브랜치는 다음 형식을 사용합니다.

```text
{type}/{issue-number}-{description}
```

예시:

```text
feature/15-member-signup
fix/23-login-error
chore/31-project-config
```

자세한 협업 규칙은 [`CONTRIBUTING.md`](./CONTRIBUTING.md)를 참고해주세요.

---

## 🔀 Development Workflow

```text
Issue
  ↓
Branch
  ↓
Development
  ↓
Pull Request
  ↓
Spotless Check
  ↓
Code Review
  ↓
Approve
  ↓
Merge Commit
  ↓
develop
```

- `main`, `develop` 직접 Push 금지
- Pull Request를 통한 코드 병합
- 최소 1명의 Approve 필요
- Spotless Check 통과 필수
- Merge Commit 방식 사용

---

## 📖 API Documentation

서버 실행 후 Swagger UI를 통해 API 명세를 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui/index.html
```

---

## 🚀 Getting Started

### Requirements

- Java 21
- PostgreSQL
- Git

### 1. Repository Clone

```bash
git clone <repository-url>
cd hobby-tree-Backend
```

### 2. Database 생성

PostgreSQL에서 다음 데이터베이스를 생성합니다.

```sql
CREATE DATABASE hobbytree;
```

### 3. Environment Variables

실행 환경에 다음 환경 변수를 설정합니다.

```text
DB_URL=jdbc:postgresql://localhost:5432/hobbytree
DB_USERNAME=postgres
DB_PASSWORD=<your-password>
```

### 4. Run

Windows:

```powershell
.\gradlew bootRun
```

Linux / macOS:

```bash
./gradlew bootRun
```

---

## 🧹 Code Style

PR 생성 전 Spotless 검사를 실행합니다.

```bash
./gradlew spotlessCheck
```

자동 포맷이 필요한 경우:

```bash
./gradlew spotlessApply
```

Windows에서는 `./gradlew` 대신 `.\gradlew`를 사용할 수 있습니다.

---

## 📄 Documentation

프로젝트의 상세 협업 규칙은 다음 문서를 참고해주세요.

- [Contributing Guide](./CONTRIBUTING.md)

---

## 📄 License

본 프로젝트의 라이선스는 [LICENSE](./LICENSE)를 참고해주세요.