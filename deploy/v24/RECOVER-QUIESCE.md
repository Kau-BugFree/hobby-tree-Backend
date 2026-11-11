# V24 quiesce 실패의 독립 승인 복구 (Issue #169)

이 도구는 ALB 제외는 완료됐지만 응답 파싱 오류로 `quiesce`가 실패한 경우를 위한 수동 복구 경로다. 사용자에게 실제 상태·새 계획·검증 결과를 제시하고 별도 복구 승인을 받은 뒤 사용한다. PR 병합/일반 CI/앱 시작은 실행 트리거가 아니다.

## 선행 조건

- ALB 빈 응답 수정 #168과 이 복구 도구를 검토·병합한다. 병합 소스로 만든 이미지와 번들을 검증한다.
- 기존 request, `plan.json`, `state.json`, 공유 active gate, 소비된 서명을 보존한다. 기존 `failed`를 `quiesced`로 수정하거나 과거 committed gate로 되돌리지 않는다.
- 이전 실패는 `quiesce`이며, 실행 승인 내역은 prepare/quiesce만 있어야 한다. 실패 처리에서 백엔드 정지·자동 시작 비활성화·DB CLI 정리가 성공해야 한다.
- 이전 이미지/컨테이너가 정지돼 있고 ALB에서 제외돼 있으며 Redis 컨테이너와 mount는 그대로 healthy여야 한다.
- V18 이력·권한과 기존 환경 설정이 그대로여야 한다. V24 가드/런타임 설정이 이미 설치됐거나 원래/새 계획의 snapshot이 존재하면 거부한다.

## 새 요청과 승인 바인딩

새 request ID와 snapshot 이름을 만든다. 새로운 plan은 원래 plan과 비교하여 request/revision/candidate/image ID/snapshot 및 `v24_host.py` 해시만 변경할 수 있다. JAR SHA, before/after schema, DB/역할, AI 설정, catalogue, 다른 도구의 해시는 같아야 한다. 이는 다른 앱 코드·스키마·권한 변경을 복구에 끼워 넣지 못하도록 하는 제한이다.

원본 실패 state/plan의 정확한 SHA256과 새 plan, 실제 실행할 `v24_recover_quiesce.py`의 SHA256을 승인서에 함께 넣는다. 승인 JSON의 정확한 필드는 다음과 같다.

```json
{
  "format": 1,
  "action": "hobbytree-v24-reconcile-quiesce",
  "request": "새 32자리 hex request",
  "plan_sha256": "새 plan SHA256",
  "recovery_sha256": "복구 스크립트 SHA256",
  "source_request": "원래 실패한 32자리 hex request",
  "source_plan_sha256": "원본 plan SHA256",
  "source_state_sha256": "원본 failed state SHA256",
  "approval_id": "새 32자리 hex nonce",
  "issued_at": 0,
  "expires_at": 0
}
```

예시의 시간/식별자는 실행 값이 아니다. 유효기간은 최대 2시간, 진입 시 5분 이상 남아야 한다. 기존 공개키에 대응하는 키로 정확한 bytes를 서명한다. 기존 phase 서명은 재사용하지 않는다. 입력과 모든 상위 경로는 root 소유이며 그룹/기타 쓰기와 symlink가 없어야 한다.

복구 스크립트는 신규 bundle 옆에 별도로 설치한다. 기존 plan의 bundle 목록/일반 전환 도구는 변경하지 않는다. 서명된 새 plan과 스크립트 해시를 먼저 인증한 다음에만 인접 프로토콜 모듈을 로드한다.

```text
python3 -B -I <protected>/v24_recover_quiesce.py <new-bundle>/plan.json <approval.json> <approval.sig>
```

## 수행 범위

1. 공유 배포 lock을 획득하고 fresh 복구 서명, 원본 prepare/quiesce 서명, 원본 failed state와 active gate 일치를 확인한다.
2. 기존 환경·가드, 정지 컨테이너·ALB 제외·Redis, 새 이미지/JAR/SQL·AI health를 관찰한다. 앱/DB/JWT/Redis Secret은 출력하거나 갱신하지 않는다.
3. 전역 nonce를 소비하고 새 request 디렉터리를 만든다. 원래 plan/state·승인서 복사본과 provenance를 저장하며 원래 실패 디렉터리는 그대로 둔다.
4. 새 이미지의 `check-before` CLI만 실행한다. 마이그레이션·DB 권한 변경·카탈로그·snapshot 생성·앱 시작·ALB 등록은 수행하지 않는다.
5. 검증 후 새 request를 `quiesced`로 기록하고 기존 durable intent 규칙으로 shared gate를 새 요청에 연결한다. `approvals`에는 `reconcile-quiesce`를 기록하며 prepare/quiesce가 실행된 것으로 꾸미지 않는다.

성공해도 백엔드는 정지 상태다. 새 계획의 verify-fence/backup/migrate/configure/activate/publish/catalogue 서명이 별도로 필요하다. owner CONNECT 차단/복원과 최소 snapshot 권한은 기존 B 전환 절차에 따라 새 계획에 바인딩한다. 실제 계정 E2E는 포함하지 않는다.

## 중단과 검증

원본 drift, 다른 DB/JAR/설정, 미완료 containment, 기존 snapshot, 이전 단계 불일치, nonce 재사용, 동시 실행, 잘못된 서명은 거부한다. 정지된 백엔드도 승인된 Compose의 마운트·메모리·실행 사용자·읽기 전용 설정·포트와 비교하고 ALB health 설정을 재검증한다.

새 요청 생성 전 `observe()`가 실패하면 원래 active gate를 유지하고 새 요청 기록은 만들지 않는다. 새 요청 생성 이후 `check-before`가 실패하면 원래 active gate를 유지하면서 새 요청에 실패를 기록한다. gate 쌍 저장 중 중단되면 intent를 보존하고 자동 재실행하지 않는다. 기존 journal의 검증된 pair-write 복구 규칙으로 실제 상태를 확인해야 한다.

테스트는 네트워크 없는 Linux root fixture에서 정상 provenance, 실제 OpenSSL 서명 변조, 잠금 경쟁, 원본/대상 drift, 상태·시간·nonce 반례, DB 관찰 실패, pair-write 중단을 검증한다. 실제 AWS 복구를 수행한 것으로 해석하지 않는다.
