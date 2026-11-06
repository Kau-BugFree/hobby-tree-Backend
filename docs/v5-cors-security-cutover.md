# 게시된 V5 CORS 보안 이미지 운영 전환

이 런북은 서울 리전 HobbyTree EC2의 단일 승인 전환을 다룬다. [기존 CORS Hotfix 런북](v5-cors-ec2-release.md)은 과거 증거다. 설치와 전환은 운영자가 별도로 승인한다.

| 용도 | OCI Revision | ECR Manifest Digest |
|---|---|---|
| 현재 운영 및 롤백 | `058c1410a9bbb4805157f6b659064440267032b3` | `sha256:11c492cc17e3fe781479bfa126bc1d6df2ec2ef3df074c4de5bd5f8d68dde608` |
| 새 보안 후보 | `598b261624a35207cc774214ef11257187dd96e8` | `sha256:8b678404cdf9db313ac1d17221db619c3bdc647983e2314c2e62f27e90fa45c2` |

두 ECR Manifest 원문을 읽기 전용으로 조회하고 SHA256 및 내부 config digest를 대조했다. 격리 CI는 그 config digest가 검증 아카이브의 Docker Image ID와 일치하는지 확인한 뒤 아카이브로 동작을 재현한다. 두 JAR의 SQL 지문은 `84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026`이며 V1~V5만 들어 있다. [게시 기록](https://github.com/Kau-BugFree/hobby-tree-Backend/actions/runs/42193275632)

## 1. 승인과 읽기 전용 준비 확인

- 변경 기록에 승인 커밋, Digest 쌍, Revision, 격리 결과, 빈 Origin 허용 목록, 단일 EC2 서비스 중단 가능성, 롤백 범위를 명시한다.
- 과거 V5 요청 및 V45 서명을 재사용하지 않는다. 이번 변경에 고유한 `숫자-숫자` 요청 ID를 발급하고 동일 ID를 다시 제출하지 않는다. 런처 확인 문자열은 이 전환에 고정되며, 별도 운영 승인은 변경 기록에 남긴다.
- 다음 명령은 기존 파일과 실행 상태를 읽는다. 검토된 Compose의 절대 경로를 `REVIEWED_COMPOSE`로 지정한다. 현재 파일의 검토 해시와 기존 컨테이너, Redis 및 unit 상태를 확인한다.

```bash
docker compose version --short
sudo docker compose --project-directory /opt/hobbytree \
  --env-file /opt/hobbytree/.env.ec2 -f "$REVIEWED_COMPOSE" config --quiet
sudo systemctl show hobbytree-backend.service \
  --property=Type,RemainAfterExit,ActiveState,SubState,FragmentPath
sudo docker inspect --format '{{.Name}} {{.Config.Image}} {{.State.Health.Status}}' \
  hobbytree-backend-1 hobbytree-redis-1
sudo stat -c '%U:%G %a %n' /opt/hobbytree/.env.ec2 \
  /etc/hobbytree/v45/config.json /run/hobbytree/v45/reader-password
sudo sh -c 'p=/var/lib/hobbytree/deployments/active.json; if test -f "$p"; then jq "{request,status,stage,rollback_stage}" "$p"; fi'
```

Compose 5.6.1은 선택적 `env_file.required: false`와 호환된다. 파일이 없을 때의 기본값은 빈 허용 목록이다. DB 비밀번호나 전체 Compose 환경은 출력하지 않는다.

## 2. 승인 파일 설치 및 마지막 Preflight

1. 승인 커밋에서 LF 원본을 내보내고, 독립적으로 검토한 SHA256 목록과 대조한다. 전송본은 root 전용 디렉터리에 보관한다. 설치 전 `/opt/hobbytree/v45-venv/bin/python -I -B -c 'import psycopg'` 및 venv 경로의 소유권·권한을 확인한다. 기존 reader 설정, CA와 비밀번호 파일을 사용한다.
2. `/var/lib/hobbytree/deployments/lock`의 비차단 독점 flock을 확보한다. 저널이 없거나 `succeeded`, `rolled-back`, `no-op`일 때만 계속한다. 기존 파일을 root 전용으로 보관한다. 잠금을 얻지 못했거나 저널이 미완료이면 중단한다.
3. 아래 순서로 승인본을 같은 디렉터리에 임시 설치한 다음 교체한다. 각 파일의 실제 SHA256과 권한을 다시 대조한다. 이미 해시가 일치하는 파일은 재설치하지 않아도 된다.

| 원본 | 설치 경로 | 소유권과 모드 |
|---|---|---|
| `scripts/ec2-cors-config.py` | `/opt/hobbytree/scripts/ec2-cors-config.py` | root:root 0500 |
| `scripts/deploy/ec2-release.py`, `v45_gate.py`, `v5-cors-database.py` | `/opt/hobbytree/scripts/deploy-cors/` 아래 같은 파일명 | 디렉터리 root:root 0700, 파일 root:root 0500 |
| `compose.ec2.yml` | `/opt/hobbytree/compose.ec2.yml` | root:root 0644 |
| `deploy/ec2-deployment-contract.json` | `/etc/hobbytree/deployment-contract.json` | root:root 0600 |
| `scripts/deploy/ec2-cors-release.py` | `/opt/hobbytree/scripts/deploy-cors/ec2-cors-release.py` | root:root 0500 |

4. 잠금 안에서 아래 Preflight를 실행한다. 실패하면 파일 설치분을 백업에서 복구하고 중단한다. 설치 중 기존 service와 컨테이너를 재시작하지 않는다. `.env.ec2`, 기존 Secret 및 SSM·V45 설치 디렉터리는 그대로 둔다.

```bash
sudo /opt/hobbytree/v45-venv/bin/python -I -B \
  /opt/hobbytree/scripts/deploy-cors/ec2-cors-release.py --preflight
```

Preflight는 호스트 설정·파일 해시, 실행 중인 V5 Digest와 Revision, ALB, 실제 Backend DB URL과 reader의 TLS/identity, Flyway 성공 이력 및 validated CHECK를 확인한다. 후보를 pull하거나 컨테이너를 생성하지 않는다. 릴리스가 시작되면 같은 검사를 반복하고, 후보 Digest와 Revision 및 두 이미지의 SQL 지문을 다시 검사한다.

## 3. 승인된 전환과 복구

별도 운영 승인을 받은 뒤, 새 요청 ID로 승인된 EC2 Session Manager 셸에서 실행한다. 허용 Origin은 빈 상태로 고정된다.

```bash
sudo /opt/hobbytree/v45-venv/bin/python -I -B \
  /opt/hobbytree/scripts/deploy-cors/ec2-cors-release.py \
  --request-id "$APPROVED_REQUEST_ID" \
  --approval v5-cors-security-no-migrations-downtime-approved
```

- 롤백 보호는 기존 V45 reader와 읽기 전용 트랜잭션을 사용한다. DB identity, V1~V5 이력, COMING_SOON 부재와 validated CHECK를 재확인하고 관계 잠금을 유지한다. 운영 Flyway는 비활성 상태이며 Hibernate는 validate를 유지한다.
- 종료 코드 0은 Health·Redis·ALB 관찰 완료다. 종료 코드 10은 이전 V5 이미지 및 설정 복구와 검증 완료다. 종료 코드 20, 미완료 저널, DB 연결 또는 보호 상실은 수동 복구 조건이다. 추가 실행을 중단하고 보호된 저널 및 실제 컨테이너 상태를 확인한다. DB 보호를 복원·검증한 뒤 [공통 수동 복구 절차](ec2-image-deployment.md)를 따른다.
- 복구 입력은 요청 디렉터리의 `settings.before`, `compose.approved.yml` 및 `cors.before`다. `cors_before_exists=false`이면 기존 CORS 파일은 없었다. 이전 이미지, Redis 및 ALB 상태가 검증될 때까지 저널을 보존한다. 이미지 복구는 DB 스키마·데이터를 변경하지 않는다.
