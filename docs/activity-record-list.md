# API-30 취미 나무별 활동 기록

> 2026-11-19 통합 계약: [활동 APIv2](api-v2-activities.md) · [코드·명세 대조/후속](activity-contract-status.md). 구현·병합과 운영 반영 상태는 구분한다.

Issue #238. develop abed9ce2(#237)의 V30·API-26~29를 기준으로 한다.
`GET /api/v1/user-hobbies/{userHobbyId}/activity-records`, JWT, HTTP200/RECORD200_5.

- ACTIVE 회원·본인 소유 나무만 조회한다. PAUSED/ENDED 나무도 읽을 수 있다. 타인/없는 나무는 TREE404_1이며 개인 결과는 Cache-Control:no-store다.
- status 생략은 전체이며 IN_PROGRESS/COMPLETED/PARTIAL/NOT_DONE 단일 값만 허용한다. size 기본20, 1~50이며 잘못된 쿼리/커서는 COMMON400_1이다.
- startedAt DESC NULLS LAST,recordId DESC로 DB에서 size+1만 선정한 뒤 페이지의 snapshot·필수 체크·피드백·성장 origin을 한 번에 projection한다. OFFSET·N+1·활동 공개/취미 지원 필터는 없다.
- format2 제목은 시작 snapshot, 진행률은 snapshot 필수 체크 완료 비율의 정수 내림이다. 선택 체크·상태 COMPLETED만으로 100%를 만들지 않는다. format2의 깨진 snapshot은 내부 정비 오류이며 현재 콘텐츠로 대체하지 않는다.
- 사용자 확정(2026-11-19): snapshot 없는 과거/체험 기록도 포함하고 현재 활동 제목을 사용한다. 알 수 없는 progressPercent와 원래 null startedAt/finishedAt/피드백을 null로 보존한다. null 시작 시각 구간은 맨 뒤의 기록 ID 내림차순이며 createdAt 등으로 치환하지 않는다. 이 nullable 의미는 FE가 표시해야 한다.
- growthApplied는 이 recordId가 실제 activity_growth_contributions.origin_record_id인지를 뜻한다. 반복 기록에 같은 tree/activity 기여가 있다는 이유로 true를 반환하지 않는다. SELF/EXPERIENCE/LEGACY 원장을 동일하게 포함한다.
- API-30 전용 파생 키 HMAC 커서는 정렬 버전·userId·treeId·status 범위·시작 시각/null 표식·recordId를 포함한다. microsecond 정밀도·정규 URL-safe encoding을 검증한다. 다른 사용자/나무/상태·다른 API·변조 커서는 거절하고 size 변경은 허용한다. 새 Secret이나 설정은 추가하지 않는다.
- 커서 anchor 행을 다시 찾지 않는다. anchor가 사라져도 저장된 정렬 키 이후를 읽는다. 각 HTTP 요청은 REPEATABLE_READ 읽기 snapshot이며 여러 페이지 전체를 고정하지는 않는다. 동시 새 기록은 앞 페이지 영역에 생길 수 있고 상태 변경은 필터 결과에 반영된다.

기존 인덱스로 나무/상태를 제한하고 DB 정렬·LIMIT을 사용한다. 대규모 자료의 정렬 인덱스 추가는 실제 실행 계획·부하 측정 후 별도 검토한다. 신규/기존 Flyway·Entity·배포/운영 설정은 변경하지 않는다. 공통 호출 제한은 기존 플랫폼 정책 범위이며 별도 API-30 limiter를 추가하지 않는다.
API-50 논리 삭제는 구현·병합됐고 현재 페이지 선정 전에 삭제 행을 제외하며 삭제된 커서 anchor에도 원래 keyset 의미를 유지한다. 현재 growthApplied는 유효 원장의 origin이며 과거 API-29 최초 응답과 다른 시점의 사실일 수 있다. 운영 AWS·Secret·DB·배포·마이그레이션·유료 모델 호출은 하지 않는다.
