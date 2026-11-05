# V5 CORS 동일 스키마 전환

이 절차는 기존 V5와 게시한 CORS Hotfix 한 쌍에 한정된다. 이전 이미지의 revision은 `058c1410a9bbb4805157f6b659064440267032b3`, digest는 `sha256:11c492cc17e3fe781479bfa126bc1d6df2ec2ef3df074c4de5bd5f8d68dde608`다. 후보의 revision은 `50f2fcd198241e8d7b6ab594057652bfc22a0d37`, digest는 `sha256:24c487f35348c34b6e2046f111f3c4457ebe68d4dde532b3b40e6d0e5b74481c`다.

## 설정과 설치 계약

- `compose.ec2.yml`, 기존 `.env.ec2` 허용 목록과 root:root 0600, 검토된 Compose·validator·systemd 해시 계약을 그대로 사용한다. `.env.ec2`에는 CORS 키를 넣지 않는다.
- 승인된 HTTPS Origin 목록은 root:root 0600인 `/etc/hobbytree/cors-origins`에 저장된다. 릴리스가 기존 읽기 전용 configtree에 `CORS_ALLOWED_ORIGINS` 파일 하나를 반영한다. 재기동 시 기존 secret fetcher가 동일 설정을 복원한다. 파일에는 비밀이 없다.
- 빈 값은 Origin 허용 목록을 비운다. 운영에서는 확정한 Origin만 쉼표로 구분해 명시한다. 개발용 `http://localhost:5173`, 와일드카드, 경로, 쿼리, 중복, 비정규 포트를 거부한다.
- 승인한 LF 원본만 설치한다. 새 `scripts/ec2-cors-config.py`와 갱신된 `scripts/fetch-ec2-secrets.py`는 `/opt/hobbytree/scripts/`에, 새 `scripts/deploy/ec2-cors-release.py`와 기존 원본 `scripts/deploy/ec2-release.py`의 복사본은 별도 root 전용 `/opt/hobbytree/scripts/deploy-cors/`에 둔다. 먼저 helper를 설치한 뒤 fetcher를 교체한다. 원본 fetcher와 파일 해시를 별도 보관한다. 기존 SSM v2/V45 파일과 systemd unit은 그대로 둔다. 설치만으로 unit을 시작하거나 재시작할 필요는 없다.
- 운영 실행 전 승인된 커밋의 SHA256으로 네 파일을 대조하고 root 소유·쓰기 권한 및 심볼릭 링크 여부를 확인한다. 전용 launcher는 실행할 때 기존 core, 설정 helper, fetcher의 해시를 다시 검사한다. 역할, 계약, 보안 그룹과 ALB를 임의로 바꾸지 않는다.

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
4. 실패 시 같은 잠금 안에서 이전 V5 이미지와 설정을 복원한다. 종료 코드 10은 이전 이미지와 설정의 복원·검증이 끝났음을 뜻한다. 종료 코드 20, 종료되지 않은 저널, 강제 종료, 파일 드리프트는 재시도 중단 조건이다. 기존 저널의 `settings.before`, `compose.approved.yml` 및 새 `cors.before`를 사용해 수동 복구한다. `cors_before_exists=false`이면 원래 CORS 파일이 없었다. 복구 중에는 persistent와 configtree 양쪽을 이전 상태로 맞추고 이전 V5 이미지, Redis ID·마운트, ALB를 확인한다.

격리 리허설은 CI용 PostgreSQL과 Redis를 사용하며 AWS·ALB·systemd를 호출하지 않는다. 보호 CHECK를 설치한 테스트 DB의 스키마는 psql로 준비하며 Flyway는 실행하지 않는다.
