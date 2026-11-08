# V18 DB 보존 재개 — 독립 조정 프로토콜 (PROPOSED)

배포 이미지/기존 bundle/SSM v1/SQL은 5ed331ace62b07e06d2d1cd3679f948e629f715b 그대로 유지한다. 이 PR은 설치·DB 변경·journal 변경·Backend 배포 승인이 아니다. 원래 request는 20261108180016-50666, Plan hash는 4866957adc987b9d58a99d6f35c6a76e049334571b79f43a3fde57cceed3eab7이다.

## 원래 실패 분석

schema-transition.py Engine.migrate의 순서는 protected inputs → stopped → fence → snapshot → check-ready → fence → db-changing → migrate → check-after → validate-app → fence → db-verified journal이다. Java migrate는 Flyway 이후 business table/sequence GRANT와 validation도 수행한다. Flyway 18개 성공만으로 controller 전체 성공을 뜻하지 않는다.

HOST.Commands는 각 명령과 phase deadline(기본1200초/개별 migration command 최대900초)을 확인하고 nonzero/timeout/raw response를 고정 오류로 감춘다. Engine.migration은 TRANSITION_PASS trailer를 요구하며 마지막 fence는 원래 fence hash와도 비교한다. snapshot/host drift, 일시적 다른 client/관리 세션, command 실패·timeout·완료 marker/응답 또는 hash 차이 등이 실패 가능한 위치다. **ResponseCode1과 현재 수동 PASS만으로 어느 위치였는지 확정하지 않는다.** 실제 원래 SSM invocation의 시간·출력과 당시 journal을 추가 확보할 수 있으면 대조하되 provider raw log/비밀번호를 공개하지 않는다.

recovery-required가 기록됐다는 사실은 recover()가 수행됐음을 뜻하지만 마지막 성공 단계나 exception 원인은 그 journal에 저장하지 않는다. db-verified journal 함수는 status를 먼저 바꾸므로, 그 journal 쓰기 실패를 현재 자료만으로 원인으로 단정하는 것도 부정확하다. 현재 check-after/validate-app/check-fence PASS는 **현재 상태의 증거**이며 원래 호출 성공 증거와 다르다.

## 읽기 전용 증거

운영자가 제공한 V1~V18/실패0/CRC일치/guard 부재/후속 PASS/세션0/containment 성공 증거를 보존한다. 실제 인계는 아래를 잠금 안에서 다시 검증한다. 이는 일반 사전 점검 반복이 아니라 DB-changing 실패 후 상태 채택에 필요한 live proof다.

| 증거 | 구현 |
|---|---|
| 고정 code/Plan/image | 원래 Plan bytes hash, 설치된 두 helper hash, candidate digest/revision/JAR/SQL, source settings/history/policy/host contract 불변 |
| 원래 실패의 승인·식별 | 두 원래 journal byte 일치, recovery-required/terminal=false/containment_failures=[], 원래 4phase의 소비 marker/context/hash |
| host maintenance | V5 container stopped/restart=no, systemd inactive/disabled, 원래 Redis ID/mount 동일, TG contract 동일, target unused/Target.NotRegistered |
| DB/RDS/snapshot | 동일 account/RDS endpoint/available, 같은 manual V5 snapshot ARN 및 fence 이후 생성시각 |
| V18 DB postconditions | 원래 check-after/validate-app를 read-only mode로 실행: 정확한 history18/CRC, 실패0, guard 없음, app role/객체 권한/JPA |
| writer fence | app CONNECT 차단, PUBLIC/다른 LOGIN 접근 차단, privileged inventory 동일, 다른 session/prepared/subscription/job 없음; **원래 journal fence hash와 동일** |

수동 check-fence PASS가 원래 hash 일치까지 포함했는지 불명확하면 그것만으로 채택하지 않는다. rdsadmin을 변경/종료하거나 privileged 예외를 늘리지 않는다. read-only probes는 Docker 임시 컨테이너/임시 JAR 파일을 쓰지만 DB/host 서비스/journal/ACL은 변경하지 않는다.

시퀀스 권한 누락 없음은 별도로 제공된 현재 SQL 결과를 감사 증거로 보관한다. 기존 validate-app는 business 테이블 권한과 JPA schema 검증 중심이며 모든 기능/sequence nextval을 실행하는 검사가 아니다. source artifact/역할/권한의 drift가 있으면 제공 결과를 재사용하지 말고 해당 항목만 읽기 SQL로 확인한 뒤 승인한다. 새로운 도구를 전체 기능 검증이나 원래 오류 원인의 증명으로 표현하지 않는다.

## 독립 승인과 새 Plan

원래 05:00:18 KST 만료 Plan의 expiry/hash/request는 편집하지 않는다. 소비한 migrate 승인도 재사용하지 않는다. 만료 전이라도 이 프로토콜은 **새 request의 format3 continuation Plan + 별도 조정 서명**을 요구한다. 새 Plan은 원래 Plan과 request/expires_at만 다를 수 있으며, 원래 available V5 snapshot을 그대로 가리킨다. V18 DB에서 새 V5 snapshot을 만들거나 migrate/quiesce를 다시 수행하지 않는다.

새 Plan은 고정 SHA worktree의 원래 prepare-schema-transition.py와 기존 test-summary/history/JAR 증거로 offline 생성한다. 새 복구 PR checkout에서는 HEAD가 달라 기존 evidence generator가 거부하므로 원래 5ed worktree를 쓴다. 기존 생성 명령의 request/output만 새 값으로 하고 snapshot에는 **hobbytree-v5-20261108180016-50666**을 명시한다. 후보/이전 digest/역할/SQL/host hash/endpoint 등은 원래와 같아야 한다. lifetime<=7200초, 시작과 인계 모두 잔여>=1200초다.

조정 승인서는 기존 schema-phase-approval.py의 6phase 형식을 재사용하지 않는다. 별도 format1(action=reconcile-v18-db-verified)이며 원래 request/Plan SHA/**현재 source journal SHA**/새 request/새 Plan SHA/새 ApprovalId/issued_at/expires_at에 서명한다. 기존 공개키 파일 hash 08f5c0df6bfbfadc4ec2a96b81e0274bcf46cb3df7f02f39cdd16622804e22a2를 고정한다. draft는 prepare-v18-reconciliation.py가 만들고 독립 WSL 승인자가 내용/실행 범위를 검토한 뒤 기존 암호화 RSA로 서명한다. 개인키는 host/Git에 반입하지 않는다. 기존 phase 승인으로 조정 action을 승인할 수 없다.

새 tool은 별도 `/opt/hobbytree/scripts/reconcile-v18/reconcile-v18.py`에 root:root0500으로 설치하는 안이다. 원래 deploy-schema/SSM v1/bundle을 수정하지 않는다. **설치 파일 SHA/절차, 조정 서명, 실제 journal 인계는 각각 승인 대상**이다. 이 PR의 코드 검토와 테스트가 그 운영 승인을 대신하지 않는다.

## 잠금·일회성·감사 인계

1. 기존 /var/lib/hobbytree/deployments/lock을 배타적으로 획득한다. 기존 source Plan/journal/context를 검사한다.
2. 새로운 Plan을 검증하고 원래와 request/expiry 외 차이를 거부한다. 새 STATE/PLANS 경로가 이미 있으면 거부한다.
3. source journal 현재 hash에 결합된 독립 서명을 검사한다. 원래 schema-approval namespace와 별도 schema-reconcile marker에 CreateNew/fsync로 승인 ID를 소비한다. 실패해도 재사용하지 않는다.
4. 위 live postconditions를 모두 검증한다. 이 도구에는 migrate/guard retirement/ACL/image 시작 entry point가 없다.
5. 새 request 디렉터리에 원래 settings.before/history/policy를 복사하고 원래 Plan/journal/조정 서명/live assessment의 변경 불가 감사 사본과 hash를 보관한다. 원래 request 파일은 수정하지 않는다.
6. 새 Plan을 새 PLANS 경로에 CreateNew로 보관한다. 새 request journal을 db-verified/terminal=false로 만들고 global active를 원자적으로 같은 새 record로 인계한다. 원래 실패 journal은 recovery-required 그대로다. 모든 판단은 서명·실검증 뒤 tool이 수행하며 수동 status 수정은 없다.
7. 기존 controller가 새 request의 verify-activation을 허용하는 상태인지 검사한다. 기존 migrate/retire/quiesce/조기 deploy와 legacy image release는 여전히 차단된다.

성공 후 정상 진행: 별도 DB owner 승인으로 app 직접 CONNECT만 허용 → **새 Plan에 대한 새 verify-activation phase 서명**/기존 installer stage-approval/SSM v1 → activation-verified → **새 deploy phase 서명** → 기존 SSM v1 deploy/150초 ALB/Redis 검증. 각 실행은 별도 승인이며 현재 작업에서 하지 않는다. 조정 승인은 Backend 시작 권한이 아니다.

## 실패와 중간 장애

- 검사·서명 실패: 유지보수와 source journal을 보존한다. migration/restore/V5 시작으로 우회하지 않는다.
- 승인 소비 후 live proof 실패: ID는 소비된 채로 둔다. 원래 active/journal은 그대로다. 새 승인에는 새 ID와 현재 source hash가 필요하다.
- 복사/Plan staging/새 journal 생성 중 실패: 생성된 부분 파일을 보존한다. source journal은 변경하지 않는다. 같은 새 request로 자동 재시도하거나 파일 삭제로 우회하지 않는다.
- global active publish 실패/timeout/강제 종료: 결과 불명이다. 두 새 journal, 원본 사본, 감사 hash를 읽어 독립 조정한다. 원래 active로 자동 덮어쓰지 않는다. 실제 다음 phase는 결과 확정 전 NO-GO다.
- 이후 verify-activation/deploy 실패: 기존 containment를 유지한다. app CONNECT가 열렸다면 별도 DB 운영자가 회수/지정 client 정리한다. 조정 도구는 ACL을 변경하지 않는다. 이 한 건용 tool로 실패를 무한 재인계하지 않는다.

## 승인 후 사용하는 명령 형태

원래 journal hash를 아직 수령하지 않아 실제 recovery Plan/승인서는 이번에 발급하지 않는다. 아래는 승인 후 사용하는 형태다.

```sh
# 새 도구의 독립 설치/파일 hash 승인 후에만. assess는 journal/서비스/DB를 변경하지 않는다.
sudo python3 -B -I /opt/hobbytree/scripts/reconcile-v18/reconcile-v18.py assess \
 --plan /root/reviewed/NEW_REQUEST.json --plan-sha256 NEW_PLAN_SHA256
# 독립 WSL 승인자. 이 명령은 unsigned 조정 승인서만 만든다.
python3 -B scripts/ci/prepare-v18-reconciliation.py \
 --source-plan SOURCE_REQUEST.json --plan NEW_REQUEST.json \
 --source-journal-sha256 SOURCE_JOURNAL_SHA256 --output-directory /secure/recovery-approvals
# 위 JSON을 독립 검토하고 WSL 암호화 키로 openssl dgst -sha256 -sign 수행.
# 원래 6phase signer로 다른 phase라고 가장해 서명하지 않는다.
# 서명된 공개 JSON/sig와 새 Plan만 host root staging에 반입한다.
sudo python3 -B -I /opt/hobbytree/scripts/reconcile-v18/reconcile-v18.py reconcile \
 --plan /root/reviewed/NEW_REQUEST.json --plan-sha256 NEW_PLAN_SHA256 \
 --approval /root/reviewed/NEW_ID.json --signature /root/reviewed/NEW_ID.sig
```

최초 운영자 명령은 **이미 설치된 Python만 사용한 원래 journal hash 캡처** 하나다. 설치/DB 재검사/서비스 변경을 요구하지 않는다.

```sh
sudo python3 -I -c 'import pathlib,json,hashlib; r="20261108180016-50666"; p=pathlib.Path("/var/lib/hobbytree/deployments"); a=(p/"active.json").read_bytes(); b=(p/r/"transition.json").read_bytes(); assert a==b; d=json.loads(a); assert d["request"]==r and d["plan_sha256"]=="4866957adc987b9d58a99d6f35c6a76e049334571b79f43a3fde57cceed3eab7" and d["status"]=="recovery-required" and d["containment_failures"]==[]; print(json.dumps({"source_journal_sha256":hashlib.sha256(a).hexdigest(),"status":d["status"],"snapshot_arn":d.get("snapshot_arn"),"fence_sha256":d.get("fence_sha256")},sort_keys=True))'
```

판정: 설계/로컬 검증은 운영 승인 준비용이다. 실제 인계·Backend 배포는 새 도구 독립 검토/설치 승인, 현재 journal hash, fresh Plan/독립 서명 및 live assessment 완료 전 **NO-GO**다. 현재 DB를 복원하거나 V5를 시작할 이유가 확인된 것은 아니다.
