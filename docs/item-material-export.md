# 준비물 전체 export v1 — BE·AI 계약

Issue #290. BE develop `bd90e56`(#289 병합), AI develop `3fbc0e6` 기준.
이번 구현은 **BE 읽기 전용 export 생산자**다. AI importer, 운영 전달/스케줄,
공급자 수집·운영 활성화를 구현하거나 승인하지 않는다. STT B는 보류한다.

## 범위와 실행 경계

- 별도 CLI이며 Spring 시작, HTTP 요청, GitHub 배포에서 자동 실행되지 않는다.
- `hobbies.is_supported=true` AND `activities.review_status='PUBLISHED'`인
  활동의 준비물 전체. 선택한 활동/취미만 추출하는 옵션은 없다.
- DB의 READ ONLY + REPEATABLE READ 트랜잭션에서 시각과 전체 목록을 읽는다.
  원본의 공개 조건·이름·버전이 동시 수정돼도 한 스냅샷을 사용한다.
- RLS로 일부 행만 보이면 `row_security=off`에 의해 오류로 종료한다.
  임의 뷰나 필터된 복제본을 원본으로 지정하지 않는다. 운영자가 service의 대상과
  sourceId의 환경 대응, 최소 SELECT 권한을 사전 검증해야 한다.
- 사용자·대화·토큰, 가격·상품·수량·필수 여부는 export 대상이 아니다.
  활동당 20개 초과여도 원본 목록은 모두 내보낸다. 추천 요청의 20개 제한과 별개다.
- 데이터/스키마/권한 오류, 잘못된 원문 이름, 행·바이트 한도 초과 시 전체 실패.
  일부 누락, 임의 정규화·잘라내기, 암묵적 빈 목록 대체는 하지 않는다.

## 문서 형식

[합성 예시](examples/item-material-export-v1.json)는 실제 사용자/DB 데이터가 아니다.
예시 ID·sourceEpoch를 운영에 재사용하지 않는다.

| 필드 | 계약 |
|---|---|
| exportId | 실행별 UUID. 전달된 파일 자체의 재시도에서는 그대로 유지 |
| sourceId | 환경별 고정 식별자. 소문자 시작, `[a-z0-9-]`, 1~64자 |
| sourceEpoch | 동일 원본 계보에 고정된 UUID. DB 복원/원본 교체 시 담당자 승인으로 전환 |
| sourceSequence | 1~9223372036854775807의 **십진 문자열**. 단일 생산자가 발급하는 증가 순번 |
| generatedAt | DB 트랜잭션 시작 시각, UTC RFC3339. 정렬/신선도 보조이며 순번 대체 불가 |
| complete | true. 제한된 범위/부분 목록을 true로 표시하는 기능은 없음. 적용 승인 의미 아님 |
| materialCount | payload.materials 길이 |
| activityCount | 준비물이 1개 이상 포함된 활동 ID의 고유 개수 |
| payloadSha256 | 아래 canonical payload UTF-8 바이트의 SHA-256, 소문자 hex |
| payload.schemaVersion | `hobbytree.item-material-export.v1` |
| payload.scope | `all-supported-published-activity-materials` |
| payload.materials | materialId **숫자값** 오름차순. 같은 이름도 원본 ID별로 모두 보존 |

각 준비물은 `materialId`, `activityId`, `hobbyId`, `activityContentVersion`, `name`만 포함한다.
ID·버전은 signed bigint 양수 범위를 손실 없이 전달하도록 십진 문자열로 표현한다.
AI 추천 API의 숫자 ID 계약을 바꾸는 것이 아니며, importer는 참조용으로 파싱해야 한다.
`name`은 PostgreSQL VARCHAR(160)의 원문(공백·대소문자 포함)이다. Python Unicode code point
1~160자, 공백만 있는 값과 U+0000~001F/U+007F는 거절한다. 잘못된 행은 고치지 않고 전체 실패한다.
이 export 검증은 기존 Java 입력 경로의 UTF-16 길이 규칙을 변경하지 않는다.

payload는 Python 3 `json.dumps(payload, ensure_ascii=False, sort_keys=True,
separators=(',', ':'), allow_nan=False).encode('utf-8')`로 직렬화한다.
키는 사전순, 공백/들여쓰기/BOM/끝 줄바꿈 없음. 배열 순서는 유지한다.
payload에는 실수 값이 없으며 문자열·객체·배열만 있다. 일반 JSON 재직렬화나 파일 전체 해시는
동일하다고 가정하지 말고 제공 fixture의 bytes/hash와 대조한다.
파일은 envelope까지 같은 형식으로 직렬화한 뒤 LF 1개를 붙인다.
해시는 전송 오류 탐지용이며 인증/서명을 대신하지 않는다. 전달 채널의 인증·접근 제어는 별도다.

## 순서와 실패 처리 — AI importer 구현 요구

순번은 DB 쓰기 없이 외부의 **단일 실행자**가 발급한다. exportId/시각/활동 버전의 최대값으로
대체하지 않는다. 현재 CLI는 순번을 발급·저장하거나 중복 실행을 잠그지 않는다.
운영 전달 도구는 순번 발급→스냅샷→파일 확정까지 단일 실행을 보장하고 이력을 보존해야 한다.
실패 시 순번을 건너뛰어도 되며, 같은 순번으로 새 스냅샷을 만들어 덮어쓰지 않는다.
재전송은 성공한 동일 파일을 사용한다. 이 운영 실행자는 후속 작업이다.

1. 허용 sourceId/epoch/schema/scope, complete, 건수·ID 유일성·정렬·hash·최대 크기를 모두 검증한다.
2. 같은 source/epoch/sequence + 같은 payload hash는 재전송으로 무변경 성공 처리한다.
   같은 순번 다른 hash, 이전 순번, 승인되지 않은 epoch는 거절한다. epoch를 자동 리셋하지 않는다.
3. AI의 공통 Python `NFKC + casefold + 공백 정리`로 query_key를 만든다.
   이름이 같은 여러 원본 참조를 모아서 관리한다. SQL lower와 혼용하지 않는다.
   정규화 후 200자 초과·빈 키 등 invalid는 잘라내거나 누락하지 말고 전체 거절한다.
4. 새 자동 항목 등록/재활성화, 모든 원본 참조에서 사라진 BE_MATERIAL 비활성화,
   수신 순번·hash 기록을 **하나의 트랜잭션**으로 반영한다.
   MANUAL·다른 공개 활동의 공유 항목은 보존한다. 실패 시 전부 rollback한다.
5. 초기 운영은 비활성화 후보를 preview 후 승인받는 방식이다. 전체 0건은 반드시 보류한다.
   대량 감소 임계치는 미확정이므로 자동 삭제 승인을 내포하지 않는다. 물리 삭제는 하지 않는다.

현재 AI `register --origin BE_MATERIAL`은 전체 importer가 아니다. 이 envelope는 의도적으로
기존 `materials`/`result.materials` 입력과 다르며 그대로 등록할 수 없다.
AI cli/catalog_store와 수신 이력 저장소의 신규 구현·테스트가 필요하다.

## 한도와 출력 파일

- 최대 10,000행, 최종 파일 **1,048,576바이트(끝 LF 포함)**. 정확히 한도는 허용한다.
  기존 AI 등록 파일의 1MiB 한도를 상한으로 채택한 v1 계약이다. 전체 목록 실측은 미수행이다.
- DB는 10,001행까지 읽어 초과를 감지한다. 10,000행만 임의 전송하지 않는다.
- 개별 추천 요청의 20개/64KiB와 별개다. 한도를 넘으면 별도 chunk/manifest 계약 후 확장한다.
- 같은 디렉터리에 임시 파일을 쓰고 fsync 후 hard-link로 완성본만 공개한다.
  기존 파일·동명 링크는 덮어쓰지 않는다. hard-link 미지원 FS에서는 실패하며 fallback하지 않는다.
  기본 POSIX 임시 파일 권한은 0600. Windows에서는 승인된 전용 폴더 ACL을 별도로 확인한다.
- 프로세스 강제 종료 시 `.material-export-*` 임시 파일이 남을 수 있다.
  importer는 최종 파일만 수신해야 하며 임시 파일은 읽지 않는다.
- DB 오류 상세·DSN·이름은 로그로 출력하지 않는다. 실패 code만 출력한다.

## 실행 예시 — 합의된 테스트 환경에서만

Python 3.11+, `python -m pip install -r scripts/item-export-requirements.txt`.
명시적 libpq service를 사용한다. DB의 앱 계정/운영 Secret을 자동으로 찾지 않는다.
비밀번호를 명령행·문서·Git에 넣지 말고 보호된 service/passfile로 전달한다.
운영에서는 TLS verify-full과 해당 4개 테이블 SELECT 전용 역할을 준비하는 별도 승인이 필요하다.

```sh
python scripts/export-item-materials.py \
  --service approved-local-test \
  --source-id synthetic-hobbytree \
  --source-epoch 11111111-1111-4111-8111-111111111111 \
  --source-sequence 1 --output /approved/test-output/materials-1.json
```

성공 exit 0: EXPORTED + 파일 바이트/행수. 실패 exit 1: 안정적인 오류 code.
파라미터 형식 오류는 argparse exit 2. 결과 파일 부모 디렉터리는 미리 존재해야 한다.

## 검증 및 남은 결정

순수 로컬 unittest: 합성 fixture/hash/Unicode·경계/기존 파일 보존/실패 무출력/실제 main 호출.
별도 PostgreSQL 17 테스트: 저장소 전체 Flyway SQL을 새 임시 DB에 적용하고 SELECT 전용 역할로
공개 조건, 공유 이름 보존, 실제 CLI service 경로, 동시 편집 snapshot, READ ONLY 강제,
RLS·권한 실패를 검증한다. 운영 DB를 조회하거나 schema를 바꾸지 않는다.

남은 공동 결정: 전달 채널·단일 실행자/순번 이력·실행 주기, AI DB 위치와 importer 담당,
정규화 후 길이/제어문자 세부 계약, 감소 임계치·승인 담당, 실제 공급자 보관·수집 허용.
추천 최대 20개·이름 160/200자 변경, AI importer 구현, STT B, AWS/운영/유료 호출은 이번 PR 범위 밖이다.

## AI팀 전달 문구

BE 공개 준비물 전체 export v1과 합성 예시를 준비했습니다. HTTP 조회 API 변경은 없습니다.
`payload.materials`에 원본 ID/활동 버전/이름이 있고 ID는 십진 문자열입니다.
AI에서는 이 계약을 검증하는 별도 전체 importer가 필요합니다. 기존 register로 직접 받을 수 없습니다.
Python 공통 정규화, MANUAL/공유 참조 보존, 이전·중복 순번 처리, 원자적 반영을 부탁드립니다.
처음에는 합성 fixture로만 검증하며 실제 전달·DB 권한·수집·운영 활성화는 별도입니다.
