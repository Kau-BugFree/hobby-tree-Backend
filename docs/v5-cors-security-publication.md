# V5 CORS 보안 후보 게시

## 검증 증거

- 후보 SHA와 게시할 전체 ECR 태그는 `598b261624a35207cc774214ef11257187dd96e8`이다.
- 검증 Run `42080492279`의 attempt 1, Artifact `13442090551`만 사용한다. [검증 실행](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/42080492279)
- GitHub ZIP SHA256은 `0112efe29bd59a44b40563779607b4988f1b8b0f17aed69f18b38465f7d4e4e9`이다.
- Image ID는 `sha256:7690824663e0cc45f5c48031b15b46ece14edf21aed885a1a4399ec7b0046767`이다. ECR Manifest Digest는 게시 후 확인한다.
- SQL 지문은 `84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026`이다. 정확히 V1~V5가 들어 있으며 V6는 제외된다.

## 승인된 수동 게시

1. PR 검사와 리뷰가 완료된 뒤 병합한다. Artifact가 만료됐거나 기존 ECR 게시 조건이 바뀌면 중단한다. 만료 예정은 2026년 11월 20일 03:08 KST다.
2. 게시 승인을 받은 운영자가 GitHub **Settings → Secrets and variables → Actions → Variables → Repository variables**에서 `V5_CORS_SECURITY_PUBLISH_ENABLED`를 정확히 `true`로 설정한다. 기본적으로 값이 없거나 `false`이면 게시할 수 없다.
3. **Actions → Publish verified V5 CORS security patch to ECR → Run workflow**에서 `develop`을 선택한다. `confirm_candidate_sha`에 위 전체 SHA를 입력한다. 기존 `hobbytree-v5-ecr-publish` Environment의 develop 전용 및 관리자 우회 금지 보호와 기존 OIDC 역할을 사용한다.
4. 검증 job은 AWS 권한 없이 GitHub 설정, 원본 Artifact 및 로드한 이미지와 JAR을 확인한다. 게시 job은 재검증 후에 OIDC를 사용한다. ECR 태그 불변성, 후보 태그 부재, 이전 이미지 보존, 기존 수명 주기·복제·EventBridge 조건이 모두 맞으면 이미지를 재빌드 없이 게시한다. 같은 태그가 이미 존재하거나 API 오류로 부재를 확인할 수 없으면 중단한다.
5. 실행 요약과 `v5-cors-security-ecr-publication` Artifact에서 `registry_manifest_digest`와 `pinned_image`를 보관한다. Workflow는 ECR Manifest의 SHA256과 내부 config digest를 검증한다. 실행 성공 여부와 별개로 게시 작업 종료 후 변수는 `false`로 복구한다. Push 직후 후속 확인이 실패한 경우 새 실행 전에 해당 태그의 존재와 Manifest를 읽기 전용으로 확인한다.
6. ECR 콘솔에서 서울 리전 `hobbytree/backend`의 정확한 Manifest Digest를 열어 스캔 완료 상태와 HIGH·CRITICAL 결과를 확인한다. 이전 Trivy OS 결과와 ECR 집계를 구분한다. 스캔 결과가 없거나 검토되지 않은 HIGH·CRITICAL 소견이 있으면 배포 승인을 보류한다.

## 운영 전환 전 조건

- 게시와 EC2 전환 승인을 분리한다. PR 롤백 CI는 고정된 두 아카이브를 로드해 동작과 복구를 검증하며, 임시 PostgreSQL에 테스트용 V1~V5 성공 이력을 심어 전후 동일성을 비교한다. 실제 Flyway의 적용 검증은 원본 검증 Run을 참조한다.
- [기존 CORS EC2 런북](v5-cors-ec2-release.md)의 런처는 이전 CORS Hotfix에 고정돼 있다. 새 Manifest Digest가 확보되면 검토된 후속 변경으로 후보 Digest와 OCI Revision을 고정하고 해시를 재검토한다. 이전 운영 이미지의 Digest, SQL 동등성, Redis·ALB·journal 보호를 유지한다.
- EC2에서 Docker Compose 2.24.0 이상과 검토된 파일의 `docker compose config --quiet`를 확인한다. 승인된 운영 Origin이 확정될 때까지 허용 목록은 비워 둔다. 운영 Flyway 작업이나 V45 RSA 승인 재사용 없이 동일 스키마 경로만 검토한다.
