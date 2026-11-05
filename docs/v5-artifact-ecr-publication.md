# 검증된 V5 Artifact의 수동 ECR 게시

이 문서는 게시 기능 준비 단계다. AWS 접속·정책 적용·ECR Push·배포를 수행하지 않았다. 게시 Workflow는 `v5-artifact-ecr-publish.yml`이며 수동 이벤트만 받는다. 별도 `v5-artifact-publication-check.yml`은 PR에서 AWS 없이 검사만 수행한다.

## 고정된 원본

| 항목 | 허용 값 |
| --- | --- |
| Backend 소스 및 OCI Revision | `058c1410a9bbb4805157f6b659064440267032b3` |
| 원본 Workflow 소스 | `4c81d57b49363f683d8ab83ba8f40263309e1d40` |
| 원본 Workflow / run / attempt | `docker-validation.yml` / `41959441820` / `1` |
| 원본 성공 job | `127022030449`, Pinned V5 image without AWS |
| Artifact ID | `13384572345` |
| Artifact 이름 | `v5-candidate-058c1410a9bbb4805157f6b659064440267032b3-41959441820-1` |
| Artifact ZIP SHA256 | `309661aff37834b30c825381aab616af87d861d42a9e01926d9d8f08ac525444` |
| 내부 archive / SHA256 | `image.tar.gz` / `aa164d6177a466b74a71c51138a5f195363fe817eea0e5766efc4ef720617961` |
| Docker image ID (config digest) | `sha256:50f0f8ba523a0a30e9b3d6219a632d754c5ee3cc65005c19b2e826404cd6f20c` |
| 플랫폼 / SQL | linux/amd64 / V1~V5, V6 금지 |
| Artifact 만료 | 2026-11-12 03:09:15 UTC / 12:09:15 KST |

원본 [Run](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/41959441820)은 attempt 1 success, Artifact 미만료 및 ZIP 다운로드 endpoint의 302 응답을 확인했다. 로컬 보관본도 ZIP/내부 archive 해시가 일치한다. 이 값은 `deploy/v5-artifact-lock.json`에 고정하며 사용자 입력으로 교체하지 않는다. 소스 Workflow의 최신 run_attempt가 바뀌거나 원본이 삭제·만료되면 실패한다. 재검증 없이 다른 run이나 로컬 파일로 대체하지 않는다.

PR #62의 실제 산출물과 Health·TLS·Flyway off·validate 증거를 재사용한다. 새 이미지를 빌드하거나 기존 Health/Flyway/Java 테스트를 게시 목적으로 반복하지 않는다. SQL fingerprint는 기존 `ec2-release.py::schema_fingerprint()`를 재사용한다. 이미지 검사 시 정지·network none 컨테이너에서 JAR만 복사하며 앱을 실행하지 않는다.

## 승인 및 준비 조건 (현재 미구성/미확인)

1. 이 문서의 새 역할·Environment 이름은 **제안값**이다. 기존 AWS 리소스의 존재를 주장하지 않는다.
2. GitHub에 `hobbytree-v5-ecr-publish` Environment를 운영자가 생성해야 한다. Required reviewers, Prevent self-review, 관리자 우회 금지와 Selected branches의 **branch develop 한 개만** 설정한다. 지정 reviewer 중 한 명의 승인이 필요하다. 이는 PR 팀원 승인과 별개인 실제 게시 승인이다.
3. Workflow는 Environment와 branch policy를 GitHub API로 검사한다. 없거나 권한 부족, reviewer/branch/우회 설정 불일치면 fail-closed다. Environment를 자동 생성해 보호 없이 실행하지 않도록 verify job이 publish job보다 먼저 확인한다. Repository variable `V5_ARTIFACT_PUBLISH_ENABLED`가 `true`일 때만 진행하며 준비 완료·별도 게시 승인 전에는 설정하지 않는다.
4. IAM에 제안 역할 `hobbytree-github-v5-artifact-push-role`을 별도 생성·검토한다. 기존 EC2/SSM 역할이나 기존 ECR 역할의 신뢰 범위를 넓히지 않는다. JSON 두 파일은 미적용 초안이다.
5. GitHub OIDC customization API에서 `use_default=true`, `use_immutable_subject=true`, `sub_claim_prefix=repo:Kau-BugFree@336851079/hobby-tree-Backend@1405678704`를 확인했다. Trust는 이 **immutable prefix + 정확한 Environment** subject와 `sts.amazonaws.com` audience만 허용한다. 기존 이름 전용 subject를 사용하지 않는다. 인증 전 이 설정의 drift도 확인한다. 실제 OIDC 토큰 발급·AWS 인증은 미실행이다. Environment subject는 branch/workflow를 자체 포함하지 않으므로 develop-only Environment 정책과 코드 리뷰가 신뢰 경계다. 다른 subject로 바뀌면 wildcard로 완화하지 않고 중단한다.
6. ECR `hobbytree/backend`가 **IMMUTABLE이고 exclusion이 없어야** 한다. 기존 설정은 AWS 접근 없이 미확인이다. 다르면 Workflow가 중단하며 변경 API를 호출하지 않는다. 이 조건을 맞추는 AWS 변경은 별도 승인해야 한다.

## 최소 권한 초안

- `v5-artifact-push-policy.proposed.json`: GetAuthorizationToken만 Resource `*`이며 서울 리전 조건을 둔다. Docker 업로드에 필요한 BatchCheckLayerAvailability / InitiateLayerUpload / UploadLayerPart / CompleteLayerUpload / PutImage와 태그 사전·사후 검증 BatchGetImage, 불변 설정 확인 DescribeRepositories는 정확한 backend repository ARN으로 제한한다.
- `v5-artifact-oidc-trust.proposed.json`: 기존 GitHub OIDC 공급자와 위 단일 Environment subject만 사용한다. EC2·SSM·RDS·Secrets Manager·IAM 변경·이미지 삭제·태그 정책 변경 권한은 없다.
- ECR의 IAM repository 권한은 특정 이미지 SHA 태그로 세분화하지 못한다. 존재하지 않는 `ecr:ImageTag` 조건을 만들지 않는다. 고정 태그는 검토된 코드와 Environment 승인으로 제한하고, 서버의 IMMUTABLE 설정으로 경쟁 실행의 덮어쓰기까지 방지한다.
- GitHub 기본 권한은 contents:read, actions:read다. actions:read는 원본 cross-run Artifact/실행 이력과 Environment 보호 설정 조회에 사용한다. publish job만 id-token:write를 추가한다. 정적 AWS Access Key는 사용하지 않는다.

## 실행 순서와 명령 (별도 게시 승인 후에만)

1. PR #62와 이 PR을 검토한다. 권장 병합 순서는 **#62 → 이 게시 PR**이다. 두 브랜치는 최신 develop에서 별도로 만들었으며 게시 코드가 #62 소스를 복제하거나 새 검증 실행을 만들지는 않는다. 원본 산출물은 #62의 미병합 커밋에서 이미 생성된 증거로 고정돼 있으므로 병합으로 원본 커밋/Artifact ID가 바뀌지 않는다.
2. 새 Workflow를 기본 브랜치 develop에 Merge Commit으로 반영한 후에만 수동 실행할 수 있다. 이번 작업에서 병합하지 않는다.
3. 위 Environment/IAM/ECR 조건을 운영자가 확인하고 별도 게시 승인을 받은 다음 enable variable을 설정한다. Artifact 보존 기간이 남아 있어야 한다.
4. Actions에서 **Publish verified V5 artifact to ECR**, branch **develop**, 고정 run/artifact와 전체 후보 SHA 확인 문자열을 지정한다. CLI 대안은 다음과 같다. **이 명령은 실제 ECR 게시를 요청하므로 지금 실행하지 않는다.**

```bash
gh workflow run v5-artifact-ecr-publish.yml \
  --repo Kau-BugFree/hobby-tree-Backend --ref develop \
  -f source_run_id=41959441820 -f source_artifact_id=13384572345 \
  -f confirmation=058c1410a9bbb4805157f6b659064440267032b3
```

5. verify job이 GitHub metadata → 정확한 ZIP → archive 해시 → 기존 evidence → docker load → 실제 ID/Revision/플랫폼/User → 이미지 JAR 목록·SQL fingerprint를 검사한다. 불일치나 V6 혼입이면 AWS 인증 전 중단한다.
6. publish job은 Environment 승인을 기다린다. 새 runner에서 Environment 설정 및 원본 Artifact/이미지를 다시 확인한 뒤에만 900초 OIDC 임시 자격 증명을 취득한다. 두 runner 사이에 변경 가능한 image/tag output을 신뢰하지 않는다.
7. ECR 불변 설정 및 **전체 후보 SHA 태그 부재**를 조회한다. 조회 권한 오류를 '없음'으로 처리하지 않는다. 기존 태그는 같은 이미지여도 차단한다. 로그인은 password-stdin, 개별 DOCKER_CONFIG만 사용한다.
8. 검증한 image ID를 전체 SHA 태그로 retag/push한다. checkout한 develop 소스는 게시 검사 코드이며 이미지 빌드 소스가 아니다. docker build/Buildx/SQL 실행·새 배포는 없다.
9. ECR BatchGetImage의 원문 manifest SHA256과 반환 imageDigest를 비교하고 manifest config.digest가 검증한 image ID인지 확인한다. Summary와 공개 `v5-publication.json`에 source/run/attempt/Artifact/Workflow 커밋/image ID/실제 Manifest Digest/digest 고정 URI를 남긴다.

## 동시 실행·실패 처리

기존 ECR Workflow와 동일한 `ecr-push-refs/heads/develop` concurrency를 공유하며 cancel-in-progress=false다. 외부 publisher와의 경쟁은 ECR IMMUTABLE이 최종 차단한다. 덮어쓰기·삭제·repair·다른 태그 재시도는 하지 않는다.

Push 도중 취소/시간 초과 또는 Push 후 manifest/로그아웃/artifact 단계 실패 시 ECR에 이미 태그가 생겼을 수 있다. 전체 실행은 실패하며 자동 삭제/재시도하지 않는다. 승인된 운영자가 CloudTrail/ECR의 실제 태그·manifest config digest를 확인해 결과를 정리해야 한다. 재실행은 기존 태그를 발견하면 중단한다. 원본 Artifact가 만료되면 새 Artifact를 임의 허용하지 말고 별도 검증·정책 검토를 다시 받는다.

Docker image ID는 config object의 SHA256, ZIP/archive hash는 전송 무결성, registry Manifest Digest는 별도 manifest 식별자다. 게시 전 Manifest Digest는 **미확정**이다. 게시 성공으로 AWS 설치/RDS V4·V5 적용/EC2 배포 승인이 생기지 않는다.

## 검증 범위 및 판정

로컬에서는 AWS 없이 정상/변조/만료/누락/다른 run·attempt·repo/Image ID/Revision/V6/SQL 변경/재게시/불변 설정/manifest 불일치/승인 보호 누락을 회귀 검사한다. 실제 AWS 호출과 OIDC는 mock으로 구분한다. AWS 없는 PR CI는 정확한 원본 Artifact를 다운로드하고 실제 Docker load/inspect/JAR 검사를 수행한다. 기존 Java·Flyway·Health 통과 결과를 다시 후보의 새 실행 결과처럼 기록하지 않는다.

Workflow 준비는 CI·CodeRabbit 검토 결과에 따라 GO 판정한다. 실제 ECR Push는 Environment/IAM/Trust subject/ECR IMMUTABLE·태그 부재/유효 Artifact/별도 승인 확인 전 **NO-GO**다. AWS 정책 시뮬레이션·실제 OIDC·ECR Push·manifest 사후 검증은 아직 미실행이다. 실제 배포도 NO-GO를 유지한다.

## 공식 근거

- [GitHub AWS OIDC 및 Environment subject](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [GitHub Environment 승인·보호 설정](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [ECR Push 최소 권한](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html)
- [ECR 태그 불변성](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html)
- [ECR 권한 및 지원 조건 키](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ecr.html)
