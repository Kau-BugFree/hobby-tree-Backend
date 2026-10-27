# develop 수동 ECR 업로드

기준 origin/develop: `a196aa4066bf0e866bcbce02a68c3f02e3ff3c81`.
Workflow: `.github/workflows/ecr-push.yml` / **Backend ECR Push**.
이 문서와 Workflow 작성 과정에서는 Git push/PR 생성, AWS 변경, 실제 이미지 업로드를 실행하지 않는다.

## 고정 대상과 인증

| 항목 | 값 |
| --- | --- |
| 저장소 | Kau-BugFree/hobby-tree-Backend |
| 허용 이벤트/브랜치 | workflow_dispatch / refs/heads/develop |
| AWS 계정/리전 | 494915081306 / ap-northeast-2 |
| ECR | 494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend |
| 역할 | arn:aws:iam::494915081306:role/hobbytree-github-ecr-push-role |
| 이미지 태그 | 실행 시점 develop의 전체40자리 GITHUB_SHA |

정적 AWS Access Key를 GitHub Secrets에 등록하지 않는다. GitHub OIDC로 900초의 STS 임시 자격 증명을 발급한다.
계정 allowlist와 audience sts.amazonaws.com을 설정했다. 토큰/비밀 키를 step output이나 artifact로 전달하지 않는다.
루트 권한은 contents:read이며 **push job만** id-token:write + contents:read를 갖는다.
테스트 job에는 OIDC 권한이 없다. pull_request/push/workflow_run 이벤트를 등록하지 않는다.
main, 기능 브랜치, tag, fork에서 실행하면 guard가 실패하고 AWS job은 실행하지 않는다.
push job에도 이벤트/저장소/ref 조건을 별도로 둔다.

사용자 제공 상태상 OIDC 공급자/역할/Push 정책은 생성 완료다. 실제 AWS 설정은 이번에 조회하지 않았다.
운영자는 역할의 신뢰 조건이 다음 값으로 제한됐는지 확인해야 한다(이 작업에서 정책은 변경하지 않는다).

- audience: sts.amazonaws.com
- subject: repo:Kau-BugFree/hobby-tree-Backend:ref:refs/heads/develop

GitHub Environment를 추가하면 OIDC subject가 바뀔 수 있다. 현재 Workflow에는 Environment를 지정하지 않는다.
ECR Push 정책에는 업로드 권한과 ecr:GetAuthorizationToken 외에 사후 검증용 **ecr:BatchGetImage**가 필요하다.
GetAuthorizationToken은 Resource *가 필요하고 저장소 작업은 해당 repository ARN으로 제한한다.
이 Workflow는 DescribeImages/CreateRepository나 RDS/EC2/Secrets Manager API를 호출하지 않는다.

## 실행 순서

1. guard: 저장소, 수동 이벤트, develop ref, 전체40자리 SHA를 검사한다. 잘못된 ref는 skip 성공이 아니라 실패로 처리한다.
2. validate: 발동한 정확한 SHA를 checkout하고 Java21 테스트/Spotless/bootJar, Python 테스트, unit 구문을 검사한다.
3. linux/amd64 이미지를 **한 번** build/load한다. revision label도 같은 SHA다.
4. 기존 CI helper/Compose를 재사용해 격리 PostgreSQL17.11에서 app TLS/health/validate/Flyway off와 실패 사례를 검사한다.
   테스트 DB/인증서/자격정보는 runner에서만 생성한다. 실제 AWS/RDS를 사용하지 않는다.
5. 검증된 이미지의 tar와 SHA-256 checksum만 artifact로 넘긴다. artifact 보존 기간은1일이다.
   CI Secret, DB 데이터, 인증서 개인 키, 로그는 artifact 경로에 포함하지 않는다.
6. push: 새 runner에서 artifact checksum, 이미지 ID, amd64와 revision label을 확인한다.
   이 job은 checkout/빌드/업무 스크립트 실행을 하지 않는다. 그 뒤에만 OIDC로 인증한다.
7. 고정 registry에 password-stdin으로 로그인하고 전체 SHA 태그 하나를 push한다. latest/단축 SHA 태그는 만들지 않는다.
8. ECR BatchGetImage로 실제 manifest를 읽고 config digest가 검증한 이미지 ID와 일치하는지 검사한다.
9. digest, SHA 태그 URI, digest 고정 URI를 로그와 Actions Summary/job outputs에 남기고 registry에서 로그아웃한다.

서로 다른 develop 수동 실행은 concurrency로 직렬화하며 진행 중 실행을 취소하지 않는다.
push job만 재실행할 때도 검증 job이 기록한 artifact 이름을 사용한다.1일 보존 기간이 지난 경우에는 전체 job을 다시 실행해야 한다.
검사/인증/로그인/push/manifest 검증 중 하나라도 실패하면 전체 실행은 실패한다.
push가 성공한 뒤 후속 검증/정리에서 실패한 경우 ECR에 이미지가 이미 존재할 수 있다. 자동 삭제·되돌리기는 하지 않는다.

이미지 전달과 업로드는 단일 linux/amd64 manifest를 대상으로 한다. push의 --platform 옵션 때문에 runner Docker API1.46 이상이 필요하다.
전체 manifest index/attestation 게시가 필요해지면 artifact 전달 및 manifest 검증 방법을 함께 변경해야 한다.

## 최초 실행 절차 (추후 사용자가 수행)

1. 기능 브랜치 `feat/github-ecr-push`의 변경을 검토하고 별도 PR로 develop에 반영한다. 현재는 commit/push/PR을 실행하지 않는다.
2. 2026-10-27 GitHub metadata에서 기본 브랜치가 **develop**임을 확인했다.
   workflow_dispatch 파일을 기본 브랜치에 반영한 뒤 수동 실행 대상으로 사용할 수 있다.
3. Actions → Backend ECR Push → Run workflow → Branch **develop** → Run workflow.
4. guard, validate, push가 모두 성공했는지 확인한다. Summary의 commit과 게시 tag가 같은 전체 SHA인지 확인한다.
5. 이후 별도 EC2 배포 승인 시 Summary의 pinned image를 BACKEND_IMAGE에 사용한다.

GitHub CLI 대안(현재 미실행):

```bash
gh workflow run ecr-push.yml --repo Kau-BugFree/hobby-tree-Backend --ref develop
```

기능 브랜치에서의 시험 실행은 guard가 거절한다. 최초 검증을 위해 branch 제한이나 IAM 신뢰 범위를 풀지 않는다.
기존 `docker-validation.yml`은 PR용으로 그대로 유지하며 AWS 권한을 추가하지 않는다.

## digest 기록과 복구

Summary 출력:

```text
Image: 494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend:<FULL_COMMIT_SHA>
Digest: sha256:<MANIFEST_DIGEST>
Pinned image: 494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend@sha256:<MANIFEST_DIGEST>
```

로컬 image ID는 registry manifest digest와 다른 값이다. config ID는 동일 이미지 검증에만 사용한다.
배포 기록에는 source commit, workflow run URL, tag/digest, 이전 정상 digest와 health 결과를 남긴다.
동일 SHA를 다시 실행할 때 ECR tag가 immutable이면 중복 태그 push는 실패할 수 있다. 태그를 자동 덮어쓰거나 삭제하도록 정책을 변경하지 않는다.
고정한 베이스 외에 apt 패키지는 변경될 수 있으므로 같은 commit의 재빌드가 항상 같은 digest라는 가정도 하지 않는다.
복구는 스키마 호환성을 확인한 뒤 이전 정상 digest로 배포 담당자가 수행한다. DB migration/롤백이나 EC2 변경은 이 Workflow에 없다.

## 작성 시점 검증 범위

정적 검사와 guard/manifest의 합성 데이터 테스트만 수행한다. 실제 OIDC, ECR 로그인/push, IAM 정책,
Docker build/컨테이너 실행은 이 준비 작업의 성공 결과로 간주하지 않는다.
실제 RDS, EC2, Secrets Manager에는 접근하지 않는다.

## 공식 근거

- [GitHub OIDC in AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [AWS Configure Credentials Action](https://github.com/aws-actions/configure-aws-credentials)
- [ECR push 권한](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html)
- [Docker image의 job 간 전달](https://docs.docker.com/build/ci/github-actions/share-image-jobs/)
