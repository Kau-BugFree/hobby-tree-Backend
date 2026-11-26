# 준비물 AI·STT 로컬 배포 패키지

Issue #303. **이 파일·CI는 AWS 설치/게시/서비스 활성화를 실행하지 않는다.**
기본 준비물 API는 저장 허용 false, worker는 profile로 제외, DB 예약은 미설치다.
STT 설정 생성 도구도 false만 출력한다. 기존 코치·추천·세션을 변경하지 않는다.

## 고정 입력과 빌드

AI `0920a3ada3b50700af61bf792c859c3606a4a1be`의 item 모듈을
`source-lock.json`의 개별 SHA-256으로 검증하여 필요한 파일만 내보낸다.
인증 파일/.env/Git은 입력 목록에 없다. 코치 전체 최신 main의 agent 변경은 가져오지 않는다.
Python/PostgreSQL 베이스는 digest 고정, PostgreSQL 16 + pg_cron은 AI 원본과 일치한다.
OS 패키지·전이 의존성은 빌드 시 해석되므로 소스 SHA만으로 동일 이미지라고 주장하지 않는다.
실제 적용에는 검증한 최종 이미지 digest와 ECR 재검사 결과를 별도로 고정한다.

```sh
python3 deploy/ai-integration/prepare_source.py /approved/ai-checkout /tmp/item-source
docker build --build-context items=/tmp/item-source -f deploy/ai-integration/item.Dockerfile -t hobbytree/item-api:local .
docker build --build-context items=/tmp/item-source -f deploy/ai-integration/catalog.Dockerfile -t hobbytree/item-db:local .
python3 deploy/ai-integration/check_containers.py --api-image hobbytree/item-api:local --db-image hobbytree/item-db:local
```

`check_containers.py`는 고유 이름의 DB 내부망·API ingress망·임시 볼륨·합성 자격증명을 만들고 종료 시 해당 자원만 제거한다.
시험 API는 호스트 loopback의 임의 포트로만 게시하고 실제 ingress 응답도 확인한다.
운영 DB/AWS/provider 호출 없음. 실제 PostgreSQL 초기화·NOSUPERUSER 계정·인증·importer·재시작 보존을 검사한다.
Linux 단위 테스트는 `PYTHONPATH=deploy/ai-integration python3 -m unittest discover -s deploy/ai-integration -v`.
UID/GID 테스트는 격리 컨테이너의 root에서 실행한다. 실제 호스트 권한/시크릿을 테스트 입력으로 사용하지 않는다.

## 파일 Secret 준비 — Linux에서 로컬 staging만

승인된 원본 디렉터리에 `item_service_token`, `item_db_password`, `catalog_admin_password`를 owner-only 일반 파일로 준비한다.
서비스 토큰은 **기존 BE와 동일한** 64~128자리 URL-safe 값이다. 임의 재생성/출력하지 않는다.
DB 암호는 서로 다른 32~128자리 URL-safe 값이며 서비스 토큰과 별도다. 커맨드라인/Compose env/CI에 쓰지 않는다.

```sh
# root인 격리 staging 환경에서만. output은 존재하지 않는 새 경로여야 한다.
python3 deploy/ai-integration/prepare_settings.py items --source /approved/source --output /approved/staged
```

출력은 0400: API용 토큰/DB 암호 UID10001, DB bootstrap용 동일 암호 사본/관리 암호 UID999.
컨테이너 안에서만 파일을 읽어 프로세스 환경에 전달한다. `docker inspect`의 Config.Env에는 값이 없다.
API/sync에는 애드픽 키를 주지 않는다. worker는 두 승인 flag가 true인 경우에만 별도 owner-only 키를 읽는다.
staging 실패 시 부분 출력은 설치하지 말고 증거로 보존한다. 원본/기존 출력은 덮어쓰지 않는다.
키 파일의 실제 전송/설치·회전·로그인/Secret API 호출은 이 도구에 없다.

## Compose와 운영 전제

`compose.items.yml`은 **기존 코치 Compose와 별도의 project**다. 기존 모델·색인·SQLite를 mount하지 않는다.
운영자가 확인한 `ITEM_API_IMAGE`, `ITEM_DB_IMAGE`의 `@sha256:` 참조, `AI_PRIVATE_IP`,
`ITEM_SECRET_DIR`, `ITEM_DB_DIR`, `ITEM_EXPORT_DIR`를 비밀값 없는 승인 파일로 지정한다.
`docker compose --profile sync --profile collection -f ... config --format json | python3 deploy/ai-integration/verify_compose.py`로 검증한다.
이 검사는 설치 승인을 대신하지 않는다. 로컬 태그/loopback/공개 IP/평문 credential env는 운영 후보 검사에서 거절된다.

- API :8001은 사설 IP 바인딩. API만 ingress bridge에도 연결하여 BE에서의 응답 경로를 제공한다.
  SG는 BE SG에서만 :8001 허용하도록 **별도 승인 후** 변경한다. API에는 provider 키가 없고 수집은 비활성이다.
- 전용 DB는 포트 미공개, 내부 Docker망만 사용한다. BE RDS에 AI 테이블을 생성하지 않는다.
- [Docker 내부망](https://docs.docker.com/reference/cli/docker/network/create/#network-internal-mode---internal)은 다른 망과의 통신을 차단하므로 API를 내부망에만 연결하지 않는다.
- 새 DB만 bootstrap. 앱 계정 item_app은 NOSUPERUSER/NOCREATEDB/NOCREATEROLE. 원본 SQL001로 자체 schema를 소유한다.
- 기존 볼륨 업그레이드는 자동 수행하지 않는다. 백업·schema diff·적용 SQL과 검증 계획을 별도로 검토한다.
- pg_cron 바이너리만 설치/preload한다. SQL002(예약/만료 정리)는 자동 적용하지 않는다.
- worker와 sync는 기본 실행 제외. worker는 기본 flag=false이며 내부망에는 외부 인터넷 경로도 없다.
  실제 수집 승인 후에만 worker 전용 egress망/보관 기간/승인 flag/키를 별도 overlay로 검토한다.
- 앱 384MiB, DB 512MiB, worker/sync 각 256MiB는 **시험 시작 한도**다.
  기존 코치6GiB+추천512MiB와 합산하면 8GB 호스트의 OS 여유를 보장하지 못한다.
  상시 공존 또는 worker 동시 실행은 메모리 실측/스케줄 분리/한도 조정이 승인되기 전 적용하지 않는다.
- 상품 미수집·보관 미승인 상태의 빈 목록을 정상 상품 추천 완료로 간주하지 않는다.

## 수동 export → 인증된 전달 → importer

단일 신뢰 운영자가 관리하는 로컬 Linux 디렉터리(0700)에 환경별 sourceId/epoch와 증가 순번을 보존한다.
여러 서버/복제 디렉터리에서 같은 원본의 export를 만들지 않는다. NFS/분산 잠금은 지원하지 않는다.
디렉터리 삭제/과거 백업 덮어쓰기로 순번을 초기화하지 않는다. 원본 복원은 새 epoch 승인 절차가 필요하다.

```sh
python3 deploy/ai-integration/export_queue.py --directory /approved/spool init --source-id approved-source --source-epoch <approved-uuid>
python3 deploy/ai-integration/export_queue.py --directory /approved/spool export --service approved-readonly
python3 deploy/ai-integration/export_queue.py --directory /approved/spool replay --sequence 1
```

`init`은 DB에 접근하지 않는다. `export`는 명시적 libpq service와 TLS verify-full,
READ ONLY/REPEATABLE READ를 쓰며 기존 공개 준비물 전체 export를 재사용한다.
운영 SELECT 권한/CA/service/passfile은 별도 준비한다. DSN/암호를 인자로 넣지 않는다.
실행 중 flock을 유지하고 DB 접속 **전에** 순번을 fsync하므로 실패/강제 종료 시 순번에 빈칸이 남는다.
`<sequence>/receipt.json`과 완성 `export.json`은 보존한다. FAILED/RESERVED를 임의 성공으로 바꾸지 않는다.
빈 목록은 HOLD_EMPTY이며 자동 반영·재전송이 불가하다. READY만 같은 파일 hash로 재전송한다.
export 디렉터리는 구성/파일시스템 백업 대상이며 조회 전용 원본 DB와 별도로 관리해야 한다.

이 패키지는 네트워크 전송을 하지 않는다. 운영자가 승인한 암호화·인증 채널(예: 비공개 S3+최소 IAM)의
전달/수신과 파일 접근 권한을 먼저 준비한다. hash는 인증을 대체하지 않는다.
AI export mount는 read-only다. 원본 ID/epoch는 전달 파일과 독립적으로 승인한 값을 사용한다.

```sh
# 준비된 동일 파일의 전체 sha256은 spool receipt에서 가져온다. 기본은 읽기 전용 preview.
docker compose --profile sync -f deploy/ai-integration/compose.items.yml run --rm item-sync sync \
  --file /exports/1/export.json --sha256 <receipt-sha256> --source-id <approved-source> --source-epoch <approved-uuid>
# 적용 승인 후 같은 인자에 --apply 추가. 비활성화가 있으면 preview의 --approve-plan <planSha256>도 필요.
```

wrapper는 한 번 읽은 바이트의 hash를 확인한 뒤 private 임시 사본을 importer에 넘겨 검증/읽기 경합을 방지한다.
실제 판정·원자적 적용·MANUAL/공유 참조 보존은 고정된 AI importer를 재사용한다.
동일 파일은 NOOP_REPLAY, 빈 전체는 HOLD_EMPTY, 과거/충돌/다른 epoch는 거절한다.
timeout/전달 중단은 DB commit 여부가 모호할 수 있으므로 **동일 파일로만** 재실행해 reconcile한다.
완료 여부를 확인하기 전에 새 파일/새 순번으로 대체하지 않는다.

## STT 설정 staging

```sh
python3 deploy/ai-integration/prepare_settings.py stt --source /approved/ai.properties --output /approved/ai.stt-disabled.properties
```

기존 14개 값은 그대로 보존하고 두 STT 키만 `enabled=false`, `request-timeout=45s`로 구성한다.
잘못된 기존 설정/부분 STT 키는 거절한다. 출력0600, 기존 출력 덮어쓰기 없음. 실제 `/etc` 설치나 재기동 없음.
GPU overlay는 기존 [compose.stt.yml](../ai-gpu-small/compose.stt.yml)과 [STT 계약](../../docs/coach-stt-gpu-rollout.md)을 재사용한다.
동일 검증 STT 이미지에만 적용하고 기존 Haiku overlay/토큰/세션 mount를 보존한다.
Google 키 10001:10001/0400 및 read-only mount, Spring45s/GPU40s/Google30s를 확인한다.
활성화는 별도 승인 후 양쪽 flag를 함께 바꾸고 BE 보호 해시/Secret 재준비/컨테이너 재생성을 포함한다.
갱신 Claude 키나 최신 agent/다음 활동 추천 코드는 이번 staging에 포함하지 않는다.

## 미확정 정책·다음 승인

1. 상품 저장/재사용 허용·보관 기간·삭제 기준 및 공급자 key. 기본86400은 허용 확정값이 아니다.
2. export sourceId/epoch/단일 담당·전달 채널·주기. 초기 수동1회 권장. 비활성화 전부 개별 plan 승인, 빈 목록 보류 유지.
3. Google STT API·결제·인식 권한은 기존 관리 조회403으로 미확인. 유료 선행시험 생략 결정은 유지하되 활성화/최초 요청 과금 승인 필요.
4. GPU 혼합 부하·메모리 예산·교체 시간과 서버 종료 조건. 현재 running 서버를 임의 정지하지 않는다.

순서: 로컬/CI/리뷰 → 사용자 병합 → 검증 digest ECR 게시/재검사 승인 → 비공개 설치·자원 시험 승인 →
원본 export/AI DB 쓰기 승인 → 상품 수집 및 STT 활성화/유료 요청 승인.
각 단계 실패 시 정지·증적 보존. 신규 item 서비스만 중지하고 기존 코치 image/config를 복구한다.
대화가 추가됐다면 SQLite/WAL 일관 백업과 역이전 검증 없이 과거 상태로 덮어쓰지 않는다.
BE는 동일 V33 이미지의 설정/보호 해시를 함께 복구하며 DB schema downgrade는 하지 않는다.
