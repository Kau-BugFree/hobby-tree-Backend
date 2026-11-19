# 활동 API 계약·구현 상태 대조

확인일 2026-11-19(Asia/Seoul), 기준 develop `9496dc25ab69905d9e0d8ac96baf0159564bd8af`와 Issue #249의 서비스 회원 검사 보완.
이 문서와 [활동 APIv2 명세](api-v2-activities.md)는 확정 계약과 코드의 현재 상태를 함께 기록한다.
운영 API/DB·실제 계정/앱 상태는 이번 작업에서 확인하거나 변경하지 않았다.

## API별 대조 결과

| API | 구현 기준 | 반영한 계약 | 구현·병합 근거 |
| --- | --- | --- | --- |
| 23 단계 목록 | HobbyService.stages | 서비스 ACTIVE 검사, 등록 단계 전체·설명null·취미별 성장 목표 | #113·#247 |
| 24 단계별 활동 | StageActivityService/StageActivityCursor | 순위 ASC NULLS LAST·ID, v2 서명 커서, v1 폐기·첫 페이지 재조회 | #116·#243 |
| 25 상세·준비물·단계 | ActivityDetailService | nullable MVP, 활동→취미 이미지, 과거 시각 보존, 미리보기와 시작 구분 | #123 |
| 26 시작·이어하기 | ActivityRecordStartService | 회원/나무 ACTIVE, 시작 스냅샷, 과거 진행409_5, 현재 유효 기여 기준 | #231 |
| 27 진행 상세 | ActivityRecordDetailService | 과거/체험 스냅샷 부재409_5, canUpdate, 삭제404 | #233·#241 |
| 28 진행 저장 | ActivityRecordProgressService | 전체 체크 교체·버전 CAS, 시간 생략/null 유지·감소 허용, 과거409_5 | #235·#241 |
| 29 완료·피드백 | ActivityRecordCompletionService | 과거 진행409_5·과거 종료409_2, 선택값 비교·최초 응답 재전송·중복 기여 방지 | #237·#241 |
| 30 기록 목록 | ActivityRecordListService/Repository | 과거 포함·미상null, 시작 시각 DESC NULLS LAST·ID, 삭제 제외·현재 origin 표시 | #239·#241 |
| 31 저장 | SavedActivityService.save | PUBLISHED+지원 취미, 실패 시 기존 북마크/시각 보존·지원 재개 | #44·#245 |
| 32 저장 취소 | SavedActivityService.unsave | 비공개/미지원/없는 원본·미저장 반복200, 본인 관계만 취소 | #46 |
| 33 저장 목록 | SavedActivityService.listMine | savedAt/ID DESC, 공개14필드·이용 불가4필드·필터 범위 커서 | #153 |
| 50 기록 삭제 | ActivityRecordDeletionService | 종료만 논리 삭제, 마지막 완료의 성장 취소, 원본 이전·최초 삭제 결과 재전송 | #241 |

위 표의 구현·병합은 실제 서버 배포나 프론트/코치 연동 완료를 뜻하지 않는다.
API-31~33의 내부 회원 검사는 #249 변경에서 보완했다. 위 표는 기준 develop의 병합 근거이며 #249의 병합·운영 반영은 별도로 확인한다.

## 회원·나무 조건

- HTTP 계약은 JWT 인증과 ACTIVE 회원 전용이다. 공통 필터는 PENDING_ONBOARDING을 통과시키므로 API별 서비스의 온보딩 검사는 필요하다.
- API-23 서비스는 ACTIVE 외 상태를 직접 거절한다. 온보딩/정지/탈퇴는 AUTH403_2/1/3, ANONYMIZED 등 나머지와 null/없는 회원은 AUTH401_2다.
- API-31~33의 requireActive도 ACTIVE만 허용한다. 온보딩/정지/탈퇴의 AUTH403_2/1/3은 유지하고 ANONYMIZED·미정 상태와 없는 회원은 AUTH401_2로 거절한다. [#249](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/249)의 직접 호출 테스트는 카탈로그·북마크·커서 미접근을 검증한다. 저장/취소는 기존 회원 행 잠금을 유지하며, HTTP 필터를 통과한 뒤 대기 중 익명화가 커밋되면 거절하고 롤백되면 정상 처리한다. 목록은 기존 REPEATABLE_READ 스냅샷에서 회원 상태를 확인하며 새로운 잠금이나 최신 상태 재검사 계약을 추가하지 않는다.
- API-23/24/25/31/32/33은 나무 보유·단계 해금을 요구하지 않는다. 카탈로그 열람/북마크는 활동 시작 허용을 뜻하지 않는다.
- API-26/28/29는 본인 ACTIVE 나무를 요구하며 API-29 재전송도 같다. API-27/30은 본인 PAUSED/ENDED 나무도 조회한다. API-50은 PAUSED/ENDED 나무의 종료 기록 삭제도 허용한다.

## 과거·삭제 기록의 오류와 조회

아래 표는 JWT·회원·소유권과 API별 나무 조건을 통과한 경우다. 409_5를 받았다고 기존 기록을 현재 콘텐츠로 다시 만들지 않는다.

| 대상 | API-26 | API-27 | API-28 | API-29 | API-30 | API-50 |
| --- | --- | --- | --- | --- | --- | --- |
| 시작 스냅샷 없는 과거 IN_PROGRESS | 시작/이어하기409_5 | 409_5 | 409_5 | 409_5 | 과거 형식은 현재 제목·미상null로 포함 | 진행 중409_6 |
| 스냅샷·최초 API-29 응답 없는 과거/체험 종료 | 새 시작은 정상 조건으로 별도 판정 | 409_5 | 종료409_2 | 최초 응답 없음409_2 | 과거 형식은 현재 제목·미상null로 포함 | 정합성이 확인되면 삭제, 불명이면409_7 |
| 삭제된 종료 기록 | recordId를 받는 API가 아니며 새 시작은 별도 판정 | 404_1 | 404_1 | 404_1 | 제외 | 본인 재요청은 최초 결과200 |

- 코드 접두어는 RECORD다. 삭제 여부는 API-27/28/29에서 나무 상태·스냅샷/종료 결과 재전송보다 먼저 확인한다. 회원 인증·소유권 검증을 우회하지 않는다.
- 과거 스냅샷 없는 진행은 일반 API로 완료/삭제할 수 없으며, 승인된 별도 정비 정책이 필요하다. 이번 문서 작업에서 정비·백필·강제 종료를 하지 않는다.
- API-30의 과거 형식은 알 수 없는 progressPercent와 원래 null 시각을 보존한다. 신규 스냅샷 형식이 손상된 경우를 과거 형식의null로 숨기지는 않는다.

## 진행·완료·성장·삭제에서 혼동하지 않을 값

- API-28 시간은 생략/null 유지, 제공값은 감소를 포함해 그대로 저장한다. 같은 체크 목록을 저장해도 성공 시 version+1이며 현재 버전이 아닌 재요청은409_3이다.
- API-29의 동일 종료 비교는 expectedVersion 제외, 선택값 생략/null 동일, 유효 feedback/notDoneReason은 상태와 관계없이 보존한다. 공백은 정규화하지 않는다. 요청 형식/양수 버전 검증은 재전송에도 필요하다.
- API-29 결과는 최초 응답의 성장 적용·단계·시각·버전을 보관한 값이다. API-50 이후 다른 기록의 현재 성장 원본이 바뀌더라도 이 과거 응답은 고치지 않는다.
- API-30 growthApplied는 현재 유효 기여의 origin_record_id가 이 recordId인지 뜻한다. API-29의 최초 growthApplied와 서로 다른 시점의 사실일 수 있다.
- 현재 유효 기여는 나무·활동 쌍당1개다. 완료 기록이 남으면 기여를 유지하고 삭제된 원본은 최소 recordId 생존 완료로 참조를 옮긴다. 마지막 완료 삭제 시 기여를 취소하고 현재 단계를 재계산한다.
- 현재 집계는 progress_stage_number가 있으면 사용하고 과거 credited_stage_number·출처·시각과 삭제 전후 감사는 보존한다. API-50 후 새 완료는 유효 기여가 없으면 다시 기여할 수 있다.
- 단계가 내려가면 새 시작의 해금 조건에 반영한다. 이미 시작한 다른 스냅샷은 기존 조건으로 이어갈 수 있다. 나무 상태와 슬롯 수, 북마크는 기록 삭제로 바꾸지 않는다.
- 현재 코치/추천은 활동 기록을 직접 조회하지 않는다. 새 이력 연동 시 삭제 제외를 적용해야 하며 기존 대화/추천 응답을 소급 변경하지 않는다. 이 연동의 E2E가 끝났다고 표시하지 않는다.

## 원본 보존과 문서 역할

- 팀 APIv2.md의 API-23~33·50만 수정한다. 활동 외 API/도메인 본문은 바이트 내용으로 대조해 보존하고 원본 백업·수정 차이를 별도로 보관한다.
- 저장소의 api-v2-activities.md는 수정한 원본 활동 파트와 같은 내용이다. 원본의 응답 예시와 기존 번호를 유지하고 새 응답 필드나 endpoint를 추정 추가하지 않는다.
- activity-start/detail/progress/completion/list/deletion, saved-activity 문서는 현재 계약과 API별 도입 이력을 구분한다. 과거 SHA·검증 수치는 당시 기록이며 현재 CI 증거로 재사용하지 않는다.
- API-25 초기 계획·성장 원장 도입 문서의 Draft/미구현 표기는 이력 절로 옮겼다. 현재 구현 상태는 위 표, 상세 계약은 활동 APIv2가 기준이다.

## 남아 있는 확인·작업

- [#126](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/126): 실제 콘텐츠 수령/정비. 기존 누락값을 임의 제목·true/false로 채우지 않는다.
- [#127](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/127): nullable 및 새 커서400 초기화의 실제 FE 확인. 사용자 MVP 결정과 역할별 수신/승인을 구분한다.
- [#129](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/129): 정비 후 공개 콘텐츠 제약 강화. API-26의 시작 거절이 이미 구현된 사실과 구분한다.
- [#130](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/130): 콘텐츠 writer/버전·복원 운영 규약. 기술 trigger·스냅샷 검증 성공을 운영 승인으로 바꾸지 않는다.
- [#242](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/242): 이미지 자산 소유권·불변 파일명·교체/삭제와 과거 스냅샷 파일 보존.
- [#249](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/249)의 API-31~33 익명화 회원 직접 호출 차단은 이번 변경에서 구현했다. PR 병합·운영 반영 완료를 뜻하지 않는다.
- 전역 COMMON429, 대량 부하, 실제 계정/앱·코치 연결 E2E, 과거 기록 정비와 운영 적용은 이번 문서 동기화의 완료 범위가 아니다.
