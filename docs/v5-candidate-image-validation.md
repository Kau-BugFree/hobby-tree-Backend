# 고정 V5 후보 Docker 검증

후보는 058c1410a9bbb4805157f6b659064440267032b3 하나로 고정한다. 사용자 입력으로 다른 SHA를 받지 않는다.
기존 docker-validation.yml의 workflow_dispatch에서 v5_candidate=true를 선택한다.
해당 분기에서는 일반 Java/Flyway/auth job을 재실행하지 않고, 후보 checkout의 Dockerfile/Compose/fixture/runtime 검사를 재사용한다.
Workflow 정의 커밋과 후보 소스 커밋은 서로 다르며 evidence.json에 각각 기록한다.

## 수동 실행

기존 Workflow 파일이 develop에 등록돼 있으므로 작업 브랜치 ref에서도 workflow_dispatch로 검증할 수 있다.
Draft PR을 병합하거나 새 Workflow를 develop에 등록할 필요가 없다.

```text
gh workflow run docker-validation.yml --ref chore/61-v5-image-validation -f v5_candidate=true
```

이 명령은 AWS 배포/ECR Workflow가 아니다. contents:read만 사용하고 OIDC/AWS 자격 증명을 요청하지 않는다.
일반 PR CI는 기존대로 별도 실행되며 그 최신 브랜치 이미지 결과를 V5 후보 증거로 사용하지 않는다.

## 검증 및 산출물

- 실제 git HEAD가 후보 SHA와 일치하고 소스가 clean인지 확인한다.
- 확인한 git HEAD를 OCI Revision으로 사용해 새 linux/amd64 이미지를 push:false/load:true로 빌드한다.
- 실제 image inspect의 Revision/플랫폼/사용자와 정지 staging container에서 복사한 JAR을 검사한다.
- JAR에 정확히 V1~V5 SQL만 존재하고 V45 AFTER fingerprint와 일치해야 한다.
- 후보의 기존 TLS PostgreSQL 17/Redis CI Compose로 시작한다. AWS RDS·운영 Redis는 사용하지 않는다.
- 기존 verify-runtime.py로 TLS verify-full/hostname 오류 거부, Flyway=false, ddl-auto=validate,
  Health/권한/메모리/로그/실패 조건을 확인한다. backend의 Flyway를 켜지 않는다.
- 이후 30초 동안 backend ID·Health·재시작 0과 Redis ID/전체 mounts 보존을 확인한다.
- 검증한 원본 image를 docker save+gzip으로 보관한다. Secret/fixture/컨테이너 설정은 artifact에 포함하지 않는다.
- image.tar.gz, SHA256SUMS, evidence.json만 GitHub artifact로 7일 보관한다.
- always cleanup은 해당 runner의 hobbytree-ci 테스트 project 및 fixture만 대상으로 한다.

로컬 Docker image ID는 config object 식별자다. archive SHA256은 다운로드 무결성 값이다.
둘 다 ECR registry Manifest Digest가 아니다. registry_manifest_digest는 게시 전 null이며 registry push는 없다.

## 다음 ECR 승인 단계

별도 승인 후 정확한 run/attempt의 artifact를 다운로드해 SHA256SUMS를 확인하고 docker load한다.
로드된 image ID, 실제 OCI Revision과 evidence를 대조한다. 검증한 동일 image를 rebuild 없이 ECR용으로 tag/push한 후
ECR에서 Manifest Digest를 조회해 확정한다. tag 변경은 image의 Revision 변경을 의미하지 않는다.

현재 ecr-push.yml은 develop의 실행 SHA를 빌드하므로 이 과거 고정 후보에 그대로 사용하지 않는다.
artifact 게시 전용 경로 또는 승인된 고정 SHA 지원을 별도로 검토해야 한다. 기존 branch/OIDC 제한을 우회하지 않는다.
GitHub artifact 만료/무결성 불일치 시 게시하지 않는다. 승인·AWS 설치·RDS migration/EC2 배포는 이 Workflow에 포함하지 않는다.
