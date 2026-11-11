# 동일 스키마 V24 이미지 교체

V24 완료 상태에서 새 앱 이미지를 기동한다. 실패 복구는 서명한 기존 V24 이미지로 돌아간다. 두 경로 모두 같은 V24 스키마와 Redis를 사용한다.

## 준비

1. #178의 병합 SHA `357b24504e6aa3f4e503156e4865e5922c2c7258`에서 amd64 후보를 빌드한다. 운영 게시 승인 뒤 기존 ECR에서 manifest digest, config ID, JAR SHA와 OCI revision을 확인한다. 후보와 이전 V24 이미지를 미리 준비한다.
2. V24의 현재 `complete` plan/state의 정확한 해시와 ALB 등록 해제 지연을 조회한다. root 보호 경로에 교체 도구와 `forward/`, `rollback/` 번들을 준비한다. `plan.json`에는 같은 V24 이력·스키마·DB 대상·AI·카탈로그 값과 새 요청 번호를 사용한다. 역사적 `before_schema`는 기존 plan 값으로 유지한다. `snapshot`은 plan 형식에 맞춘 요청 이름이다.
3. 두 번들의 `v24_host.py`와 `v24_transition.py`만 이 PR의 검토본으로 교체한다. 각 artifact 해시를 plan에 기록한다. 기존 스키마 도구 및 나머지 파일은 원본과 일치해야 한다.
4. forward의 `previous`와 `previous_image_id`는 현재 이미지, `candidate`와 관련 필드는 새 이미지다. rollback의 previous는 forward 후보, candidate는 원래 V24 이미지다. rollback 후보의 digest, config ID, JAR SHA, revision은 원래 값과 일치해야 한다.

manifest JSON에는 `format=1`, `source_request`, `source_plan_sha256`, `source_state_sha256`, `forward_plan_sha256`, `rollback_plan_sha256`, `drain_seconds`를 넣는다. 승인 JSON에는 `format=1`, `action=hobbytree-v24-image-exchange`, `direction`, `manifest_sha256`, `tool_sha256`, `approval_id`, `issued_at`, `expires_at`을 넣는다. 각 방향의 번들에 `activate.approval.json/.sig` 및 `publish.approval.json/.sig`도 둔다. 이 네 단계 승인은 기존 `hobbytree-v18-v24` 형식이며 해당 번들의 plan SHA와 새 요청 번호에 묶는다.

forward 실행 시 `rollback/exchange.approval.json`과 `rollback/exchange.approval.sig`를 함께 검증한다. 서명 6개는 서로 다른 nonce를 사용한다. 각 방향의 실행 제한은 20분이다. forward의 드레인 직전에 rollback 승인과 하위 단계 서명에 두 방향의 실행 시간과 실패 정리 3분이 남아 있는지 확인한다. 승인 수명은 최대 2시간이다.

## 실행

~~~text
python3 -B -I <protected>/v24_exchange_image.py <protected>/exchange.json <forward-approval.json> <forward-approval.sig>
~~~

공유 배포 lock을 유지한다. 이전 컨테이너·ALB·Redis·호스트 파일·DB 대상·두 이미지의 JAR와 V24 SQL 지문을 대조한다. ALB 등록 해제 완료 뒤 백엔드만 정지하고, 검토된 두 도구·이미지 참조·요청 포인터를 교체한다. 기존 V24 boot ticket으로 후보를 기동한다. 현재 DB 이력과 권한, JPA 검증은 기존 활성화 검사를 사용한다.

후보 공개 전에 내부 헬스, 무토큰 JWT 401, Expo Web CORS, Swagger 출처, 허용하지 않은 출처 차단을 확인한다. 성공하면 ALB에 등록하고 150초 안정성을 확인한 뒤 새 journal을 `complete`로 기록한다. 서비스의 시작과 종료는 기존 systemd unit을 사용한다.

## 실패 후 복구

단계 실패 시 journal에 실패를 기록한다. ALB가 drain 중이면 백엔드를 계속 둬 이미 라우팅된 요청이 종료될 수 있게 한다. 새 상태의 원본 실패 증거와 대상 상태를 확인한 다음 다음 명령을 실행한다.

~~~text
python3 -B -I <protected>/v24_exchange_image.py <protected>/exchange.json <protected>/rollback/exchange.approval.json <protected>/rollback/exchange.approval.sig
~~~

같은 manifest에 묶인 forward 실패와 서명 소비 기록을 검증한다. 알려진 기존 컨테이너, 소유권 ledger로 확인한 후보 또는 확인된 컨테이너 부재를 지원한다. 실패 후보가 공개된 경우 등록 해제 완료를 먼저 기다린다. 원래 설정과 이미지를 새 요청으로 복원한다. 기존 V24 이미지는 CORS 복구 전 동작으로 돌아간다. 원본과 실패 journal은 보존한다.

기동 실패 뒤 티켓이 남아 있으면 실패 요청·기동 서명·소비 기록·boot ID가 일치하는지 확인한다. 드레인 및 정지 확인 뒤 티켓을 복구 journal에 보관하고 원래 티켓을 폐기한다. 일치하지 않는 티켓은 거부한다.

제어 프로세스의 강제 종료, 승인 만료, 불일치한 소유권, 완료되지 않은 journal pair 갱신은 자동 복구를 거부한다. 이 경우 실제 상태와 기록을 대조한다. DB에 적용된 SQL, DB ACL, 데이터, Redis 컨테이너 및 볼륨은 교체 대상에 포함되지 않는다.

운영 ALB에서 마지막 확인한 등록 해제 지연은 300초다. 직전 기동 50초와 공개 시 초기 헬스 확인 약 23초를 기준으로 정상 전환의 접속 중단을 약 6~8분으로 예상한다. 150초 안정성 확인을 포함한 검증 완료는 약 9~11분이다. 이미 공개된 후보에서 복구하면 두 번째 드레인이 추가될 수 있다.
