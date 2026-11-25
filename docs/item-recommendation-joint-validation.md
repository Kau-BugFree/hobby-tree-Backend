# 준비물 추천 공동 검증 자료

Issue #292. BE `1ee535f`(#291 병합), AI `3fbc0e6`의 계약을 기준으로 준비한 자료다.
실제 사용자·상품·URL·운영 DB를 사용하지 않는다. `.invalid` URL은 호출 대상이 아니다.
STT B·실제 서버 기동·운영 변경·공급자 호출은 보류한다.

## 1. AI팀에 전달할 파일

- [기존 export v1 계약](item-material-export.md)
- [공동 시나리오 및 기대 결과](examples/item-sync-v1/scenarios.json)
- [초기 전체 export](examples/item-sync-v1/01-initial.json)
- [공동 fixture 디렉터리](examples/item-sync-v1/)

`scenarios.json`의 `initialSource`는 합성 원본 테이블 값이며 각 배열의 의미는 다음과 같다.

- hobbies: `[hobbyId, isSupported]`
- activities: `[activityId, hobbyId, reviewStatus]` (각 취미의 합성 0단계 사용)
- materials: `[materialId, activityId, name]`

실제 importer 입력은 각 `file`이 가리키는 export JSON이다. manifest 자체를 가져오지 않는다.
기존 단일 예시와 다른 독립 시나리오이므로 AI 테스트 DB의 원본 순번 이력을 초기화한 후 사용한다.
모든 sourceId·epoch·ID는 합성 전용이며 운영 값으로 쓰지 않는다.

## 2. 원본 변경 시나리오

초기 AI 카탈로그에는 `독서등`과 `수동 유지` 두 검색어를 **MANUAL**로 준비한다.
아래 번호 순서로 실행하며, 검토가 필요한 단계는 테스트 전용 명시적 승인을 거친 뒤 다음 단계로 간다.
`expectedAi`는 **AI팀이 구현·검증할 기대값**이며 실제 importer 성공 결과가 아니다.

| 순번 | 파일/원본 변화 | 기대 결과 |
|---|---|---|
| 1 | `01-initial.json`: 공개 4개 준비물, 비공개·미지원 각 1개 제외 | 동일 이름 계량스푼의 원본 참조 2개 보존, 검색어는 하나. MANUAL 유지 |
| 2 | `02-add.json`: 독서 활동에 독서등 추가 | 등록 참조 추가, 기존 독서등의 MANUAL 출처 유지. 해당 활동의 기존 준비물 버전도 함께 증가 |
| 3 | `03-rename.json`: 피크 → 기타 피크 | 새 키 등록, 기존 자동 피크는 비활성화 후보로 검토 |
| 4 | `04-hide-shared.json`: 첫 계량스푼 활동 비공개 | 다른 공개 활동이 참조하므로 계량스푼 유지 |
| 5 | `05-hide-last-reference.json`: 마지막 공유 활동 비공개 | 계량스푼 비활성화 후보로 검토 |
| 6 | `06-republish.json`: 첫 활동 재공개 | 계량스푼 재활성화, 원본 ID 유지·활동 버전 증가 |
| 7 | `07-unsupported.json`: 악기 취미 지원 중단 | 기타 피크 비활성화 후보로 검토 |
| 8 | `08-supported-again.json`: 악기 지원 복원 | 기타 피크 재활성화, 버전 증가 반영 |
| 9 | `09-empty.json`: 전체 취미 지원 중단 | 완결된 빈 목록이지만 적용 보류. 카탈로그와 마지막 수신 순번 8 유지 |

`activeKeysAfterApprovedApply`는 해당 단계 승인·반영 후의 기대 active 집합이다.
`manualOriginsPreserved`의 출처를 자동으로 BE_MATERIAL로 바꾸면 안 된다.
`deactivationCandidates`는 물리 삭제 목록이 아니며 자동 적용 승인이 아니다.

## 3. 전달 오류 시나리오

마지막 승인 상태를 순번 8로 둔 뒤 `deliveryCases`를 각각 독립 실행한다.
모든 사례에서 카탈로그·수신 순번 8을 그대로 보존해야 한다.

| 입력 | 기대 판정 |
|---|---|
| `08-supported-again.json` 재전송 | 동일 순번·동일 hash: NOOP_REPLAY |
| `03-rename.json` 역순 전달 | REJECT_STALE |
| `same-sequence-conflict.json` | 같은 순번·다른 유효 hash: REJECT_SEQUENCE_CONFLICT |
| `incomplete.json` | complete=false: REJECT_INCOMPLETE |
| `hash-mismatch.json` | payload 변조: REJECT_HASH |
| `unknown-epoch.json` | 미승인 원본 계보: REJECT_EPOCH |

판정 이름은 공동 테스트의 의미상 라벨이다. AI 운영 HTTP 상태/오류 코드를 새로 확정한 것이 아니다.
비활성화와 수신 이력 저장 중 실패를 주입해 전체 rollback도 AI측에서 추가 검증해야 한다.

## 4. 추천 응답 경계

BE의 AI 응답 상한은 **UTF-8 262,144바이트(256KiB)**다. 문자 수나 Content-Length만으로 판단하지 않는다.
공동 fixture는 20개 준비물 × 5개 상품, 한글 상품명·최대 4096자 이내 HTTPS URL,
가격·이미지 null 등을 포함하며 아래를 loopback HTTP로 검증한다.

| 상황 | BE 기대 결과 |
|---|---|
| 262,143 / 262,144바이트, 고정 길이·chunked 전송 | 성공, 100개 상품·URL·한글·null 값 보존 |
| 262,145바이트 | 수신 중 차단, HTTP 502 / ITEMREC502_1, 자동 재시도 없음 |
| 에러 HTTP 응답 본문도 상한 초과 | 수신 상한 적용, ITEMREC502_1 |
| 요청 limit=1/3/5에 상품 2/4/6개 응답 | ITEMREC502_1; 임의로 앞의 N개만 잘라 전달하지 않음 |
| 버전/순서/준비물 누락·null 원소·빈 offerRef·HTTP 구매 URL·잘못된 JSON 타입 | ITEMREC502_1 |
| malformed JSON, 초과 본문, 초과 상품 수의 외부 API 경로 | 공통 ApiResponse HTTP502, result 없음, 원본 진단/상품 정보 비노출 |

개별 상품 필드가 유효해도 전체 100개 URL이 각각 4096자이면 응답 합계가 상한을 넘을 수 있다.
현재는 이 경우 502를 반환한다. 응답 상한 확대·필드 축약·상품 수 조정은 이번에 변경하지 않았으며,
실제 AI 상품 fixture를 연결한 뒤 별도 정책으로 결정한다. 구매 URL을 임의 절삭해서는 안 된다.

Java fixture 생성기: `ItemRecommendationBoundaryFixtures`.
검증: `ItemRecommendationResponseBoundaryTests`, `ItemRecommendationSpecFixtureIntegrationTests`.
상품 수 초과 검사만 production validator에 추가하며 기존 timeout·응답 크기·null 허용 계약은 유지한다.

## 5. 검증 범위와 다음 작업

- BE: fixture 재생성 일치·건수/hash·기대 집합 정합성, 실제 격리 PostgreSQL의 9개 원본 변화와
  export 원문/버전/전체 payload 일치 검증. 모든 기존 Flyway를 합성 DB에 적용한다.
- BE: mock AI의 응답 경계 및 실제 Controller→Service→Client→공통 예외 처리 확인.
- 미검증: AI importer, 실제 공급자, 운영 전달/스케줄, 실 사용자 앱 E2E.
- 다음: AI팀 importer에 위 파일을 입력해 기대 판정·DB 상태·실패 rollback 확인 →
  두 서비스가 사용하는 격리 카탈로그로 추천 조회 확인 → 운영 연결 계획 별도 승인.

재생성(합성 JSON 파일만 기록, DB/네트워크 없음):

```sh
python scripts/tests/build_item_material_export_cases.py
python -m unittest discover -s scripts/tests -p 'test_item_material_export*.py' -v
```

DB 검증은 `ITEM_EXPORT_TEST_DSN`으로 명시한 disposable localhost PostgreSQL 17에서만 실행한다.
미설정 시 DB 테스트는 skip되므로 순수 로컬 성공을 DB 검증 완료로 표시하지 않는다.
기존 Docker CI의 격리 migration job은 이 변수와 테스트 전용 인증을 주입해 실제 DB 테스트를 실행한다.
