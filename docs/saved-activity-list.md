# API-33 저장한 활동 목록

> 2026-11-19 통합 계약: [활동 APIv2](api-v2-activities.md) · [코드·명세 대조/후속](activity-contract-status.md). 구현·병합과 운영 반영 상태는 구분한다.

회원 검증 보완 #249: 서비스에서도 ACTIVE만 허용하며 ANONYMIZED·미정 상태는 AUTH401_2로 거절한다. 기존 온보딩/정지/탈퇴 오류를 유지하고 커서 해석·목록 조회 전에 차단한다. REPEATABLE_READ 조회 스냅샷은 유지하며 병합·운영 반영은 별도로 확인한다.

Issue #150 / PR #153을 재사용한다. 최신 develop b796d2ac(V31·API-50)과 APIv2 API-33 기준으로 재검증한다.
`GET /api/v1/users/me/saved-activities`, JWT, HTTP200/SAVED200_3, Cache-Control:no-store.

- ACTIVE 회원만 서비스에서도 확인하고 JWT userId로 저장 관계를 제한한다. 온보딩 미완료·정지·탈퇴는 기존 AUTH 오류다. 나무 보유 여부/성장 해금과 무관하게 본인의 북마크를 읽는다.
- hobbyId 선택(양수), size 기본20·1~50, 서버 발급 cursor를 사용한다. 생략과 빈 문자열은 구분하며 빈/잘못된 값은 COMMON400_1이다. hobbyId에 해당하는 저장 관계가 없으면 빈 목록이다.
- savedAt DESC,activityId DESC의 keyset으로 DB size+1만 읽는다. 실제 마이크로초와 동시각 tie를 보존하고 size 변경은 허용한다. anchor의 저장 관계가 사라져도 토큰의 정렬 키로 이어간다. 여러 페이지 전체를 고정하지 않으며 새 저장/재저장은 이전 페이지 영역에 생길 수 있다.
- HMAC은 기존 서비스 키에서 API-33 전용 도메인으로 파생한다. userId·hobbyId(생략=0)·정렬 버전·savedAt·activityId를 서명하며 위조·다른 범위/API·비정규 encoding/시각·과도한 길이를 거절한다. 새 Secret/설정은 추가하지 않는다. 기존 정상 발급 커서 형식은 유지한다.
- PUBLISHED이면서 지원 취미인 활동은 명세의14개 필드를 반환한다. 시간은 expected_total_minutes, 비용은 estimated_cost_min이며 API-17/24와 같은 저장 값이다. 알 수 없는 값은 null이고 임의 추정/0을 만들지 않는다. 활동 썸네일이 없으면 취미 썸네일을 쓴다. 외부 단계 번호는 내부+1이다.
- 비공개/미지원 항목은 activityId·savedAt·isAvailable:false·안내 제목의4필드만 반환해 저장 취소가 가능하게 한다. 원문 제목·소속·이미지·시간/가격 등 숨긴 메타데이터는 노출하지 않는다. 물리 삭제된 카탈로그는 기존 FK cascade로 저장 관계도 제거된다.
- 회원 확인과 projection을 REPEATABLE_READ 읽기 snapshot으로 처리한다. 조회는 저장 시각이나 저장 관계·나무·활동 기록을 변경하지 않는다. API-50 기록 삭제와 API-32 북마크 취소는 독립이며 기록 삭제가 북마크를 해제하지 않는다.

기존 Issue·브랜치·PR 이력을 보존한 일반 merge로 최신 develop을 반영했다. 과거 CodeRabbit의 실제 DB 경계 검증·테스트 이름 지적을 반영하고 현재 검증은 사용자 지정 Codex 리뷰를 사용한다. 기존 V1~V31·Entity·운영 설정·배포 trigger는 변경하지 않는다. 공통 COMMON429 정책·대량 부하 검증은 별도 후속이다. 운영 AWS·DB/Secret·배포·유료 모델 호출은 하지 않는다.
