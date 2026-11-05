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

## 수동 게시 정책

1. `hobbytree-v5-ecr-publish` Environment는 Selected branches의 **branch develop 한 개만** 허용하며 관리자 우회는 비활성화한다. 이 Environment는 팀 합의에 따라 Required reviewers 없이 사용할 수 있다. 운영자는 Repository variable을 활성화하고 고정 SHA를 입력해 게시를 요청한다. reviewer를 지정한 경우 Prevent self-review도 확인한다.
2. verify job은 Environment와 브랜치 정책을 GitHub API에서 확인한다. 조회 실패나 정책 불일치 시 게시 전에 중단한다. Repository variable `V5_ARTIFACT_PUBLISH_ENABLED`의 정확한 값 `true`가 필요하다.
3. Workflow는 기존 역할 `arn:aws:iam::494915081306:role/hobbytree-github-ecr-push-role`을 사용한다. V5 게시에 쓰는 Trust 조건은 `sts.amazonaws.com` audience와 `repo:Kau-BugFree@336851079/hobby-tree-Backend@1405678704:environment:hobbytree-v5-ecr-publish` subject다. 인증 전에 GitHub의 immutable subject 설정을 다시 검사한다. Environment subject에는 브랜치가 포함되지 않으므로 Workflow와 Environment가 각각 `develop`을 제한한다.
4. 서울 리전 ECR `hobbytree/backend`에는 **IMMUTABLE 및 exclusion 없음**이 필요하다. 정책이 다르거나 후보 태그가 이미 있으면 게시를 중단한다.

## 최소 권한 초안

- `v5-artifact-push-policy.proposed.json`: GetAuthorizationToken만 Resource `*`이며 서울 리전 조건을 둔다. Docker 업로드에 필요한 BatchCheckLayerAvailability / InitiateLayerUpload / UploadLayerPart / CompleteLayerUpload / PutImage와 태그 사전·사후 검증 BatchGetImage, 불변 설정 확인 DescribeRepositories는 정확한 backend repository ARN으로 제한한다.
- `v5-artifact-oidc-trust.proposed.json`은 기존 역할에 설정할 V5 Environment trust 조건을 보여 준다.
- ECR의 IAM repository 권한은 특정 이미지 SHA 태그로 세분화하지 못한다. 고정 태그는 Workflow에서 검사하고 ECR의 IMMUTABLE 설정으로 경쟁 실행의 덮어쓰기를 막는다.
- GitHub 기본 권한은 contents:read, actions:read다. actions:read는 원본 cross-run Artifact/실행 이력과 Environment 보호 설정 조회에 사용한다. publish job만 id-token:write를 추가한다. 정적 AWS Access Key는 사용하지 않는다.

## 실행 순서와 명령 (별도 게시 승인 후에만)

1. 수정 PR을 `develop`에 반영하고 원본 Artifact 유효기간을 확인한다.
2. 실제 게시 직전에 **Settings → Secrets and variables → Actions → Variables → Repository variables**에서 이름 `V5_ARTIFACT_PUBLISH_ENABLED`, 값 `true`를 설정한다. Environment variable에 입력하면 첫 검증 job이 이 값을 읽을 수 없다.
3. Actions에서 **Publish verified V5 artifact to ECR**, branch **develop**, 고정 run/artifact와 전체 후보 SHA 확인 문자열을 지정한다. 다음 명령도 실제 ECR 게시를 요청한다.

```bash
gh workflow run v5-artifact-ecr-publish.yml \
  --repo Kau-BugFree/hobby-tree-Backend --ref develop \
  -f source_run_id=41959441820 -f source_artifact_id=13384572345 \
  -f confirmation=058c1410a9bbb4805157f6b659064440267032b3
```

4. verify job은 GitHub 원본 metadata, ZIP·archive 해시, 이미지 ID·Revision·플랫폼·사용자, JAR의 SQL 목록과 fingerprint를 검사한다.
5. publish job은 새 runner에서 Environment 설정과 원본 Artifact·이미지를 다시 확인한 뒤 900초 OIDC 임시 자격 증명을 취득한다.
6. ECR 불변 설정과 후보 SHA 태그 부재를 확인한다. 태그가 이미 있거나 조회에 실패하면 게시를 중단한다. 로그인은 password-stdin과 전용 DOCKER_CONFIG를 사용한다.
7. 검증한 이미지에 전체 SHA 태그를 붙여 게시한다.
8. ECR이 반환한 manifest의 SHA256과 config digest를 확인한다. Summary와 `v5-publication.json`에 원본 Run·Artifact와 게시된 Manifest Digest·URI를 기록한다.
9. 실행이 종료되면 같은 Repository variable을 삭제하거나 `false`로 바꿔 다음 게시 요청을 닫는다. 이미 시작한 실행을 중단해야 하면 해당 Actions run을 취소하고 ECR 태그 상태를 확인한다.

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
