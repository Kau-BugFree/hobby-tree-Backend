# V5 CORS 동일 스키마 전환

이 문서는 기존 CORS Hotfix의 검증 기록이다. 운영 V5에서 새 보안 패치 이미지로 전환하려면 [보안 이미지 운영 전환 런북](v5-cors-security-cutover.md)을 따른다.

이 절차는 기존 V5와 게시한 CORS Hotfix 한 쌍에 한정된다. 이전 이미지의 revision은 `058c1410a9bbb4805157f6b659064440267032b3`, digest는 `sha256:11c492cc17e3fe781479bfa126bc1d6df2ec2ef3df074c4de5bd5f8d68dde608`다. 후보의 revision은 `50f2fcd198241e8d7b6ab594057652bfc22a0d37`, digest는 `sha256:24c487f35348c34b6e2046f111f3c4457ebe68d4dde532b3b40e6d0e5b74481c`다.

## 설정과 설치 계약

- 기존 `.env.ec2` 허용 목록과 root:root 0600을 유지한다. `.env.ec2`에는 CORS 키를 넣지 않는다. 두 V5 이미지의 검증기가 configtree 파일 목록을 고정하므로 Secret 디렉터리도 그대로 둔다.
- Compose가 선택적 root:root 0600 파일 `/etc/hobbytree/backend-cors.env`에서 `CORS_ALLOWED_ORIGINS` 한 줄만 읽는다. 파일은 릴리스가 원자적으로 갱신하고, 재기동에도 같은 Compose가 해당 값을 읽는다. 파일에는 비밀이 없다.
- 빈 값은 Origin 허용 목록을 비운다. 운영에서는 확정한 Origin만 쉼표로 구분해 명시한다. 개발용 `http://localhost:5173`, 와일드카드, 경로, 쿼리, 중복, 비정규 포트를 거부한다.
- 승인한 LF 원본만 설치한다. 새 `scripts/ec2-cors-config.py`는 `/opt/hobbytree/scripts/`에, 새 `scripts/deploy/ec2-cors-release.py`와 기존 원본 `scripts/deploy/ec2-release.py`의 복사본은 별도 root 전용 `/opt/hobbytree/scripts/deploy-cors/`에 둔다. launcher는 root:root 0500으로 설치하고, 실행 전 승인 커밋의 해시를 대조한다. launcher는 core, helper, Compose의 해시를 다시 검사한다.
- 먼저 운영 Docker Compose가 선택적 `env_file.required`를 지원하는지 확인한다. 공유 배포 잠금 안에서 기존 Compose와 계약을 보관하고 검토된 `compose.ec2.yml` 및 `deployment-contract.json`을 함께 설치한다. 바뀐 해시는 Compose 한 개다. 현재 컨테이너에 CORS 값이 없고 설정 파일도 없는 상태에서 기존 구성의 결과가 그대로인지 검증한다. 실패 시 두 원본 파일을 함께 복구한다. 설치 단계에서는 컨테이너나 systemd를 재시작하지 않는다.

## 승인 후 실행과 복구

1. 승인된 운영 Origin, 현재 이전 V5 digest 및 RDS V1~V5 성공 이력, `hobbytree_v45_rollback_guard`가 검증됐는지 확인한다. 기존 보안 스캔의 HIGH 소견은 보안 검토와 승인 없이 예외 처리하지 않는다.
2. 기존 사용자 세션과 배포 상태를 확인하고, 동일 스키마·이전 이미지 롤백·단일 EC2 중단 가능성을 별도로 승인한다. 이 절차는 기존 Workflow의 SSM 문서를 변경하지 않는다. 승인된 Session Manager root 셸에서만 실행한다.

   ```bash
   sudo /usr/bin/python3 -I -B /opt/hobbytree/scripts/deploy-cors/ec2-cors-release.py \
     --request-id "$APPROVED_REQUEST_ID" \
     --approval no-migrations-backward-compatible-downtime-approved \
     --origins "$APPROVED_ORIGINS"
   ```

3. 기존 core가 V5 SQL 지문, digest 및 revision, 잠금, 현재 설정, ALB와 Redis 보존을 확인한다. Hotfix 기동 후 승인 Origin의 preflight와 무인증 GET 401을 확인한다. V45 승인 ID와 Flyway 명령은 사용하지 않는다.
4. 실패 시 같은 잠금 안에서 이전 V5 이미지와 설정을 복원한다. 종료 코드 10은 이전 이미지와 설정의 복원·검증이 끝났음을 뜻한다. 종료 코드 20, 종료되지 않은 저널, 강제 종료, 파일 드리프트는 재시도 중단 조건이다. 기존 저널의 `settings.before`, `compose.approved.yml` 및 새 `cors.before`를 사용해 수동 복구한다. `cors_before_exists=false`이면 원래 CORS 파일이 없었다. 이전 V5 이미지, 컨테이너 환경과 설정 파일, Redis ID·마운트, ALB를 확인한다.

격리 리허설은 CI용 PostgreSQL과 Redis를 사용하며 AWS·ALB·systemd를 호출하지 않는다. 보호 CHECK를 설치한 테스트 DB의 스키마는 psql로 준비하며 Flyway는 실행하지 않는다.

[격리 리허설](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/42077615608)에서 설정 파일이 없을 때 기존·변경 Compose의 Backend 환경이 동일함을 확인했다. 이전 V5는 설정이 있어도 preflight를 401로 처리했다. Hotfix는 승인 Origin에 200, localhost에 403, 미인증 GET에 401을 반환했으며 컨테이너 재생성 후에도 같은 결과를 냈다. 실패 주입 후 이전 V5와 설정을 복원했다. 실제 호스트 재부팅은 별도 운영 점검으로 남긴다.

EC2의 읽기 전용 확인 명령은 `docker compose version --short`다. 선택적 `env_file.required`에는 Docker Compose 2.24.0 이상이 필요하다. 승인된 후보 Compose 파일의 절대 경로를 `REVIEWED_COMPOSE`로 지정하고, 현재 서비스에 영향을 주지 않는 구문 검사를 실행한다. [Docker Compose env_file 문서](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)

```bash
sudo docker compose --project-directory /opt/hobbytree \
  --env-file /opt/hobbytree/.env.ec2 -f "$REVIEWED_COMPOSE" config --quiet
```

## 이미지 보안 판정

두 ECR digest의 완료된 스캔에서 같은 HIGH 10건이 보고됐다. 패키지 항목의 중복을 제거하면 PCRE2 4개와 APT 1개 CVE다. Hotfix에서 추가된 HIGH나 CRITICAL은 관찰되지 않았다.

- PCRE2 `10.39-3ubuntu0.1`의 스캔 소견 중 일부는 특정 네이티브 정규식 처리에서 메모리 손상과 코드 실행 가능성이 있다. Java CORS 처리 경로가 해당 네이티브 함수를 호출한다는 증거는 확인하지 못했다. Canonical의 Jammy 수정 패키지는 `10.39-3ubuntu0.2`다. [Ubuntu PCRE2 공지](https://ubuntu.com/security/notices/USN-8974-1)
- APT `2.4.14`의 `CVE-2026-109518`은 패키지 취득·설치 경로에 영향을 준다. 애플리케이션 런타임은 APT를 실행하지 않고 비루트·읽기 전용이다. Canonical의 Jammy 수정 패키지는 `2.4.15ubuntu0.1`이다. [Ubuntu APT 공지](https://ubuntu.com/security/notices/USN-8996-1)

현 게시 Digest의 운영 적용은 보안 대응 또는 명시적 예외 승인 전까지 보류한다. 패치가 필요한 경우 새 Digest를 별도로 만들고 스캔 및 이미지·스키마·롤백 검증을 다시 한다. 이 작업에서 게시된 이미지는 재빌드하지 않는다.
