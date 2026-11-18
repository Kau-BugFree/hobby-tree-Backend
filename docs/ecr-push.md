# Backend main 릴리스

최신 실행 계약은 [main-release.md](main-release.md)를 따른다.

기능 PR → develop 검증 → 사용자의 develop→main Merge Commit → main SHA 최종 검증·artifact → 보호된 Environment의 사용자 승인 → ECR 게시·고정 SSM 문서의 Flyway/배포 순서다.

`.github/workflows/ecr-push.yml`은 이름을 유지하되 main 릴리스 워크플로로 전환했다. 기존 develop 수동 게시/SSM v2 호출을 이 경로에서 사용하지 않는다. `HOBBYTREE_MAIN_RELEASE_ENABLED`가 true가 아니면 운영 job은 실행하지 않는다. 이 변경만으로 Environment·IAM·SSM·운영 가드가 설치되지는 않는다.

과거 V5/V18/V24 도구와 증거는 해당 문서를 참조한다. 과거 검증 성공을 이번 main 경로의 운영 성공으로 재사용하지 않는다.
