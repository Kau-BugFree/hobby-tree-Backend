# main 완료 이미지의 부팅·장애 복구

Issue #187. 코드와 격리 검증 범위이며 운영에 설치하거나 활성화하지 않았다.

## 동작

`hobbytree-main-runtime.service`는 docker/network-online 이후 실행된다. timer는 부팅 30초 이후와 서비스 종료 60초 이후에 상태를 확인한다. 두 경로 모두 동일한 root 실행기로만 들어간다. 기존 배포와 같은 `/var/lib/hobbytree/deployments/lock`을 비차단 잠금하며 배포 중이면 다음 주기를 기다린다.

완료된 main 상태만 허용한다. blocked/failed/진행 중 배포는 자동 복구하지 않으며 migration·unfence·snapshot 복원·상태 초기화를 호출하지 않는다. 복구 과정은 release active.json을 수정하지 않는다. 정상 컨테이너에는 Secret 교체나 재생성을 하지 않는다.

설치본 해시·보호 파일·인스턴스·구형 서비스 비활성 상태·현재 이미지와 Compose·Redis 기존 볼륨을 확인한다. 변경이 감지되면 사전 단계에서 중단한다. 복구가 필요하면 durable 이력으로 10분간 최대 3회를 허용한다. 시간이 지나면 다음 timer에서 다시 확인한다. 정상 health 확인은 시도 예산을 소비하지 않는다.

복구 순서: ALB 제외/배수 → 현재 backend/Redis 정지 → 앱 DB/auth 및 migration Secret을 AWSCURRENT로 조회하고 AI 파일 검증 → 전부 유효한 뒤 `/run` 파일 재작성 → 이전 완료 이미지의 digest/ID/revision/SQL 검증 → DB runtime-check → 기존 볼륨으로 Redis 재생성 → backend 재생성 → 내부 health/JWT/CORS → ALB 등록/공개 검증.

Redis도 복구 중 정지될 수 있다. 비밀번호 회전·재부팅으로 사라진 bind mount를 다시 읽도록 재생성하지만 `hobbytree_redis_data` 볼륨을 삭제하거나 새 볼륨으로 대체하지 않는다. 볼륨이 없으면 실패한다. 이력/JPA/CONNECT가 맞지 않으면 DB 값을 변경해 고치지 않고 중단한다. 활성 API에 유료 AI 호출이나 계정 쓰기를 하지 않는다.

복구 단계 실패는 보호된 runtime journal에 고정 단계명만 기록하고 다시 ALB 격리·소유 컨테이너 정지를 시도한다. Secret 값·AWS 응답·예외 원문은 출력하지 않는다. API 제한/네트워크/Secret 오류에 오래된 값을 fallback으로 사용하지 않는다. systemd/EC2 강제 종료로 중단되면 다음 실행은 완료된 이미지와 실제 DB를 다시 검증한다. 운영자는 반복 실패 journal 및 systemd 상태를 감시해야 한다.

## 설치 전 조건과 별도 승인

1. #67 경로의 첫 main 배포를 포함해 `runtime-check`가 들어간 이미지가 정상 공개되고 complete 상태가 된 다음 활성화한다. 이전 V24 이미지에 새 CLI가 있다고 가정하지 않는다. 최초 인계와 첫 배포 사이에는 자동 복구를 활성화하지 않는다.
2. 현재 Compose의 backend와 Redis restart 정책을 모두 `no`로 승인하에 고정하고 설정 해시를 갱신한다. Docker가 `/run` Secret 준비 전에 컨테이너를 자동 시작하지 않도록 실제 컨테이너 정책도 확인한다. 프로젝트/볼륨/Secret 경로는 기본 `hobbytree`, `hobbytree_redis_data`, `/run/hobbytree/{secrets,redis-secrets,migration-secrets}`만 지원한다. 다른 경로는 적용 전 별도 변경한다.
3. root 소유 실행 파일 설치: `scripts/deploy/main_runtime.py`, `main_release_host.py`를 `/opt/hobbytree/scripts/main-release/`로, 기존 `fetch-ec2-secrets.py`, `validate-ec2-config.py`를 `/opt/hobbytree/scripts/`로 설치한다. `/etc/hobbytree/main-release.json`의 runtime.sources에 실제 Git blob/설치 바이트 SHA256을 기록한다. 다른 소유자·symlink·그룹/타인 쓰기는 거부한다. 설정은 root:root 0600이다.
4. runtime.app_secret/auth_secret/migration_secret은 실제 서울 계정의 서로 다른 정확한 ARN을 기록한다. 사용자명은 기존 app_user/migration_user와 대조한다. EC2 역할의 해당 세 Secret GetSecretValue 및 필요할 경우 해당 KMS key Decrypt만 검토한다. GitHub 역할에는 Secret 권한을 추가하지 않는다. ARN/정책 실값은 미확인이며 설치 승인 시 최소 권한 diff를 제시한다.
5. 기존 DB/AI/CORS 원본 설정과 CA를 그대로 보호한다. 재부팅 후 DB/auth/Redis/AI 파일 및 migration 두 파일은 기존 fetcher로 다시 준비한다. 앱 uid10001, Redis uid999, 파일0400·디렉터리0500을 검증한다. 원본 AI 파일은 `/etc/hobbytree/ai.properties` root0600으로 유지한다.
6. unit/service와 timer를 설치하되 초기 disabled로 둔다. `systemd-analyze verify`, 실제 소유권/해시, 현재 상태/볼륨/DB 읽기 확인을 통과한 뒤 runtime.enabled=true, service와 timer enable/start를 별도 승인받는다. 기존 backend.service는 disable/inactive 유지하며 두 서비스가 동시에 기동하도록 만들지 않는다.
7. 최종 승인된 유지보수 창에서 실제 재부팅을 검증한다. ALB 배수·OS/네트워크·Secret API·앱 기동까지 포함하므로 기존 이미지 교체 중단시간을 그대로 약속하지 않는다. 실제 EC2 재부팅·유료 호출·실계정 검증은 이번 격리 테스트에 포함하지 않는다.

## 검증과 복구

로컬/CI는 AWS 응답을 모의화한다. Linux root 격리 파일 테스트에서 `/run` 소실을 두 번 재현해 실제 파일 권한/Redis ACL을 확인한다. PostgreSQL 격리 테스트는 runtime-check가 CONNECT를 복구하거나 마이그레이션을 실행하지 않고, 이력 변조/CONNECT 차단을 거부하며 현재 JPA를 검증하는지 확인한다. systemd unit 문법을 CI에서 검사한다. 실제 systemd 부팅과 AWS 정책은 운영 전 사전검증 및 승인 후 유지보수 창에서 최종 확인한다.

복구를 중지하려면 먼저 timer와 service를 정지/비활성화하고 공유 lock이 풀렸는지 확인한 뒤 runtime.enabled=false로 설정한다. 미완료 DB 상태를 complete로 바꾸거나 Secret을 임의 생성하지 않는다. 이전 운영 경로 복귀는 보존한 unit/drop-in/상태/동일 이미지와 권한을 대조하는 별도 승인 절차다. 실패 시 증거 파일은 삭제하지 않는다.

승인 구분: A 설정/실행기/Secret 최소 권한·unit 설치(비활성), B 정상 main 배포 이후 runtime 활성화와 실제 재부팅 검증. AI 모델·데이터 적재·유료 호출은 포함하지 않는다.

## oneshot 최종 점검

`scripts/deploy/runtime_verification.py`는 운영자의 읽기 전용 검증에 사용하는 공통 도구다.
서비스 제어, Secret 조회, 파일 교체, 재부팅을 실행하지 않으며 runtime 실행기에 설치할 필요가 없다.
`inactive`가 보일 때까지 공유 배포 lock을 잡은 채 기다리지 않는다. 타이머가 `activating`인
경우 먼저 lock 없이 기다리고, 잠금 확보 전후와 DB/이미지/HTTP 점검 후 동일한 완료 실행인지 확인한다.
실행이 바뀌면 그 점검 결과를 버리고 lock을 해제한 뒤 다시 기다린다.

```python
result = verify_oneshot(
    systemd_snapshot,
    lambda: deployment_lock(validated_existing_lock_path),
    inspect_current_release_read_only,
    timeout=180,
)
```

호출자는 잠금 경로의 소유권·symlink·권한, 설치 해시, 현재 완료 이미지·SQL·DB·Secret 파일·ALB와
기존 실패 이력 보존을 계속 검증해야 한다. `inspect`는 조회만 수행하고 I/O마다 제한 시간을 설정한다.
확인 결과는 `result['inspection']`이며 검증한 실행의 boot/시작/종료/결과는 `result['invocation']`에 남긴다.
현재 boot의 해당 실행 시간 범위에 속한 journal만 허용한다. 종료 코드 0만으로 성공 처리하지 않는다.
`RUNTIME_BUSY`는 대기 대상으로 남고, 실패·rate limit·알 수 없는 결과·시간 초과는 점검을 중단한다.
기본 허용 결과는 `RUNTIME_HEALTHY`다. 실제 복구 완료를 검증하는 경우에만 호출자가
`accepted={'RUNTIME_HEALTHY', 'RUNTIME_RECOVERED'}`를 명시한다.

이전에 성공한 실제 재부팅과 실패한 최종 점검 기록은 삭제하거나 덮어쓰지 않는다. 재활성화 계획은
현재 boot/컨테이너와 기존 `runtime-status=publish/done`, attempts 및 복구 journal을 새 기준으로 고정한다.
이력이 있다는 이유로 초기화하지 않으며, 재활성화 중 새 복구가 발생하면 정상 앱 무변경 점검의 성공으로
간주하지 않는다. 코드·격리 테스트 통과는 C 재활성화나 추가 재부팅 승인이 아니다.
