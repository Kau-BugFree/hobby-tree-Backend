# V24 configure 실패 복구

대상은 backup 복구 요청이 스냅샷과 V24 이력을 검증한 뒤, 모든 호스트 파일을 설치하고 configure 단계의 최종 검증에서 실패한 경우다. 현재 설치가 실패 요청의 bundle과 일치해야 한다. 빈 `ExecReload`가 `systemctl show` 응답에서 생략되면 D-Bus의 정확한 배열 타입과 빈 값을 확인한다. reload 명령이 있거나 조회 결과가 불명확하면 거부한다.

## 서명과 번들

기존 실패 plan을 복사해 `request`, 그에 맞는 `snapshot` 이름, `bundle.v24_host.py` 해시를 변경한다. `snapshot` 필드는 plan 형식에 맞춘 새 요청 이름이다. 실제 백업 증거는 원본 요청의 스냅샷 ARN과 생성 시각을 다시 검증해 새 journal에 보존한다.

새 bundle에는 검토된 `v24_host.py`와 나머지 원본 파일을 둔다. bundle 옆에 `v24_recover_configure.py`와 이전에 실행한 `v24_recover_backup.py`의 정확한 바이트를 둔다. 이전 도구의 고정 SHA와 새 복구 도구·plan의 서명을 검증한다.

새 승인은 `format=1`, `action=hobbytree-v24-reconcile-configure`, `request`, `plan_sha256`, `recovery_sha256`, `source_request`, `source_plan_sha256`, `source_state_sha256`, `approval_id`, `issued_at`, `expires_at`을 포함한다. 기존 승인 키로 서명하며 새 nonce를 사용한다. 유효기간은 최대 2시간이며 진입 시 5분 이상 남아야 한다.

~~~text
python3 -B -I <protected>/v24_recover_configure.py <new-bundle>/plan.json <approval.json> <approval.sig>
~~~

## 적용과 검증

1. 운영 적용 승인 후 보호된 경로에 서명과 bundle을 전달한다. 전역 배포 lock을 획득하고 이전 요청과 공유 gate가 같은지 확인한다.
2. 원본 서명과 소비 기록, V24 설치 파일, 기존 백업, 정지된 백엔드, Redis, ALB 제외, AI health와 후보 이미지 신원을 확인한다.
3. 새 journal에 기존 실패 증거를 복사한다. `check-fence`, `check-after`, `validate-app`으로 현재 V24 이력과 DB 차단·스키마를 확인한다.
4. 설치된 `v24_host.py`를 검토본으로 교체하고 요청 포인터와 새 `configured` 상태를 게시한다. DB 이력과 권한은 유지된다.
5. 운영자가 기존 승인 절차로 앱 DB CONNECT를 복원하고, 새 요청에 묶인 `activate`, `publish`, `catalogue` 서명을 각 단계에서 사용한다.

검증 실패 시 중단한다. 원본 실패 journal과 소비된 서명을 보존한다. 파일 교체 중 실패하면 설치 상태와 요청 포인터를 확인한다. journal 게시 도중 중단되면 기록된 pair intent를 검사한다. 앱 시작에는 일치하는 공유 gate, 별도 활성화 서명과 부팅에 묶인 ticket이 필요하다.
