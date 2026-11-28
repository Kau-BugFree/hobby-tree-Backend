# API-29 활동 완료·피드백·성장

> 2026-11-19 통합 계약: [활동 APIv2](api-v2-activities.md) · [코드·명세 대조/후속](activity-contract-status.md). 구현·병합과 운영 반영 상태는 구분한다.

Issue #236. develop 9f0a015의 API-26~28·V29를 기반으로 한다.
`PATCH /api/v1/activity-records/{recordId}/complete`, JWT, HTTP200/RECORD200_4.

- ACTIVE 회원·본인 기록·ACTIVE 나무를 회원→나무→기록 순서로 잠그고 재검증한다. COMPLETED 성장에서는 이후 hobby SHARE로 현재 activities_per_stage(k)를 고정한다.
- 첫 종료는 IN_PROGRESS·시작 snapshot·expectedVersion을 검증한다. COMPLETED는 모든 필수 체크가 필요하며 선택 단계·경과 시간은 성장 조건이 아니다. PARTIAL/NOT_DONE은 종료하되 성장에 반영하지 않는다.
- COMPLETED/PARTIAL은 feedback 필수, 제공한 feedback는 항상 점수1~5·난이도 EASY/APPROPRIATE/HARD를 검증한다. memo는 최대1000 Unicode code point, 이유는 제공 시 공백만 아닌1~200 code point이며 NOT_DONE에서 필수다. 원문 공백은 보존한다. PostgreSQL에 저장할 수 없는 NUL·깨진 surrogate는 RECORD400_3으로 거절한다.
- JSON 타입·정수 묵시 변환·필수 version 오류는 COMMON400_1, 종료 의미·조건부 피드백/이유 오류는 RECORD400_3이다.
- 나무별 원장 고유 기여 N과 현재 단계 bucket을 확인하고 정상 기준선 `storedStage=min(5,floor(N/k))`, `currentBucket=N-storedStage*k`에서만 새 성장을 쓴다. 기존 k를 사용하며 3을 하드코딩하지 않는다. 현재 V27/API-39 방식과 같은 누적 공식이며 응답 단계도 저장값과 같다.
- 원장과 기존 growth_counted/growth_stage_number mirror 불일치·비정상 기준선은 전체 rollback 및 COMMON500_1 내부 정비 오류다. 과거 값을 재계산·보정하거나 불일치를 max()로 숨기지 않는다. k 변경/운영 자료 정비는 별도다.
- 최초 COMPLETED만 pair당 기여1개와 mirror를 기록한다. 반복/체험에서 이미 인정된 pair는 새 기록을 종료하되 추가 기여·승급하지 않는다. 실제 API-39 외부 증명 미연동503을 우회하지 않는다.
- 버전 CAS·서버 finishedAt·피드백/이유·기여·나무 단계·최초 결과 저장은 한 트랜잭션이다. 시각은 DB clock_timestamp/startedAt/직전 updatedAt+1µs 중 최댓값이며 실제 저장 정밀도로 응답한다.

## 사용자 확정 보완 — 2026-11-19

- snapshot 없는 과거 진행은 보존하고 RECORD409_5로 완료를 차단한다. 최초 API-29 응답 없는 과거/체험 종료는 RECORD409_2이며 가짜 성공 응답을 만들지 않는다.
- 동일 종료 비교는 expectedVersion 제외, 선택 필드 생략/null 동일이다. 상태와 무관하게 유효한 선택 feedback/notDoneReason을 보존하고 문자열 공백까지 비교한다. 다른 종료는 RECORD409_2다.
- 동일 재요청은 최초 result를 그대로 재전송한다. 당시 growthApplied/count/단계/version/시각을 현재 값으로 다시 계산하거나 성장 기여를 추가하지 않는다. 재전송도 ACTIVE 회원·소유권·ACTIVE 나무 조건을 검증한다.

## V30·범위

V30은 nullable 미수행 이유와 불변 activity_completion_receipts만 추가한다. V1~V29 및 기존 행·체험 종료·원장 값은 변경하지 않고 과거 이유/응답을 백필하지 않는다. 만족도·난이도·메모는 기존 컬럼을 재사용한다. receipt 수정/직접 삭제는 차단하며 기존 회원/나무 purge cascade는 유지한다.
API-30·API-50은 구현·병합됐고 실제 외부 참여 증명은 별도 후속이다. 현재 owner 조회에서 삭제 기록을 먼저 제외해 완료 receipt 재전송으로 삭제를 되돌리지 않는다. API-50으로 기여가 취소된 뒤 새 기록 완료는 유효 기여가 없으면 다시 기여할 수 있다. 실제 활동 hard delete는 기존 FK로 계속 차단된다.
테스트는 TEST ONLY 콘텐츠·격리 PostgreSQL/Redis·테스트 인증만 사용한다. 운영 AWS·Secret·DB·배포·마이그레이션·유료 모델 호출은 하지 않는다.
