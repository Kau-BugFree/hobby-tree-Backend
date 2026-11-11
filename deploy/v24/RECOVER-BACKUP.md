# V24 backup 실패의 독립 승인 복구 (Issue #173)

대상은 quiesce 복구 후 verify-fence는 통과했으나 backup이 실패한 요청이다. 원본 실패와 소비된 서명은 그대로 두고, 동일한 앱·DB 계획의 새 요청을 만든다. 일반 CI, PR 병합, 앱 시작이 실행 트리거가 아니다.

## 허용되는 상태

- 원본은 `failed/backup`, 승인 목록은 `reconcile-quiesce`, `verify-fence`, `backup` 세 개뿐이다.
- 원본 quiesce 복구의 서명, 원래 prepare/quiesce 두 서명, verify-fence/backup 서명 및 다섯 소비 마커를 모두 검증한다. 원본 실패 두 세대와 보관 사본의 해시도 대조한다.
- database/exclude/disable/stop containment가 모두 성공했고, DB CLI 기록은 check-before/check-fence만 허용한다. snapshot 또는 이후 단계의 사실이 기록되어 있으면 거부한다.
- V18 이력·소유권과 기존 DB writer fence가 그대로이며 앱 CONNECT는 차단되어 있어야 한다.
- 이전 컨테이너 정지, ALB 제외, 기존 Redis 정상 및 mount 내용 일치, 기존 설정·가드 유지, AI health 정상이어야 한다.
- 원래 quiesce 요청, 실패한 backup 요청, 새 요청의 snapshot은 모두 없어야 한다. 이미 생성된 snapshot을 추정으로 채택하지 않는다.

이 도구는 다른 단계의 실패, 불명확한 snapshot 생성 결과, 반복 backup 복구, 이미 적용된 migration을 처리하지 않는다.

## 새 계획과 서명

새 plan에서 변경할 수 있는 필드는 `request`, 이에 대응하는 `snapshot` 두 개뿐이다. revision/image/JAR/schema/bundle/DB 역할/AI/catalogue는 원본과 같아야 한다. 복구 도구는 bundle 옆에 별도로 두며 일반 전환 도구나 bundle 목록은 수정하지 않는다.

```json
{
  "format": 1,
  "action": "hobbytree-v24-reconcile-backup",
  "request": "새 32자리 hex request",
  "plan_sha256": "새 plan SHA256",
  "recovery_sha256": "실행할 v24_recover_backup.py SHA256",
  "source_request": "실패한 backup request",
  "source_plan_sha256": "원본 plan SHA256",
  "source_state_sha256": "원본 failed state SHA256",
  "approval_id": "새 nonce",
  "issued_at": 0,
  "expires_at": 0
}
```

예시는 실행 가능한 승인서가 아니다. 기존 공개키로 검증되는 새 서명이 필요하며 유효기간은 최대 2시간, 진입 시 5분 이상 남아야 한다. 이전 서명은 역사적 증거로만 검증하고 재사용하지 않는다. 기존 quiesce 복구 도구의 검증된 SHA는 코드에 고정되어 있다. 이를 변경하려면 별도 코드 검토가 필요하다.

```text
python3 -B -I <protected>/v24_recover_backup.py <new-bundle>/plan.json <approval.json> <approval.sig>
```

신규 plan과 복구 스크립트의 해시 및 서명을 먼저 검증한 다음에만 인접 모듈을 읽는다. root 소유·보호된 경로, 일반 파일, 전역 배포 lock과 nonce의 배타적 생성이 필요하다.

## 실행 범위와 중단

1. 원본 state/active 일치, 서명·소비 기록·보관 사본, 새 요청 부재를 확인한다.
2. 정지 runtime과 ALB/Redis/AI/이미지/스키마 및 관련 snapshot 부재를 읽기 전용으로 확인한다.
3. 새 nonce와 요청을 만들고 원본 plan/state, 설정, legacy gate와 새 서명을 보관한다.
4. `check-before`와 `check-fence` CLI만 실행한다. 새 fence fingerprint가 원본 verify-fence 값과 달라지면 중단한다.
5. 새 요청을 `quiesced`로 게시한다. 원본 fence 검증 시각이나 snapshot 성공 사실을 새 요청으로 승계하지 않는다.

성공 후에도 앱은 정지이고 DB fence는 유지된다. 새 verify-fence → backup → migrate → configure → activate → publish → catalogue 서명이 각각 필요하다. 성공한 백업이 확인되기 전에 migration을 실행할 수 없다. DB CONNECT 복원은 정상 전환의 configure 이후 절차를 따른다.

입장 전 실패하면 새 요청·nonce를 만들지 않는다. 새 요청 생성 후 DB 조회가 실패하면 원래 active gate를 유지하고 새 요청에 실패를 남긴다. journal pair 쓰기 중 중단되면 intent를 보존하고 일반 pair-write 규칙으로 검사하며 자동 재실행하지 않는다. DB 권한 변경, 스냅샷 생성, 마이그레이션, 앱 시작, ALB 등록은 이 도구가 수행하지 않는다.

## 스냅샷 권한의 별도 확인

실제 CreateDBSnapshot AccessDenied는 정책 문서 또는 IAM 시뮬레이션 성공으로 해소됐다고 간주하지 않는다. 승인된 DB ARN과 새 snapshot ARN에만 `rds:CreateDBSnapshot`을 허용하는 기존 최소 범위를 유지하고, 조회 없이 `Resource: *`나 삭제·복원 권한을 추가하지 않는다.

- 설치 전 ARN/리전/만료·정확한 호출 역할을 대조하고 원본 정책을 보관한다.
- 설치 후 `GetRolePolicy`로 내용 일치를 확인한다. 임의의 제안 정책을 추가한 시뮬레이션과 실제 설치된 역할만 대상으로 한 시뮬레이션을 구분한다.
- 실제 역할의 두 ARN별 결정과 MissingContextValues를 모두 확인한다. 시뮬레이터는 실제 서비스 호출 성공의 증거가 아니다.
- IAM은 전파 지연이 있을 수 있으므로 변경 직후 문서 조회 성공만으로 실제 적용을 단정하지 않는다. 타임스탬프와 정책 검증 결과를 보존한다.
- backup의 실제 API가 거부되면 중단하고 CloudTrail의 principal/리전/오류와 해당 시각 정책을 대조한다. 거부를 성공으로 기록하거나 같은 nonce로 재시도하지 않는다.
- 임시 권한은 성공·실패 후 정확한 정책만 제거한다. 추가 태그 권한 등은 실제 필요가 입증된 경우에만 범위를 따로 검토한다.

참고: [RDS 작업과 리소스 권한](https://docs.aws.amazon.com/service-authorization/latest/reference/list_rds.html), [IAM 변경의 전파 지연](https://docs.aws.amazon.com/IAM/latest/UserGuide/troubleshoot.html).
