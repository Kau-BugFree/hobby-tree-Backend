# 활동 관련 제휴 콘텐츠 조회

`API.md`의 API-49 명세를 따른다.

## 구현 범위

```http
GET /api/v1/activities/{activityId}/sponsored-content
Authorization: Bearer {accessToken}
```

```json
{
  "isSuccess": true,
  "code": "SPONSORED200_1",
  "message": "활동 관련 제휴 콘텐츠를 조회했습니다.",
  "result": {
    "activityId": 1003,
    "items": [
      {
        "sponsoredContentId": 12001,
        "title": "입문용 드로잉 키트",
        "description": "연필과 스케치북을 함께 준비할 수 있어요.",
        "sponsorName": "예시 파트너",
        "thumbnailUrl": "https://cdn.example.com/sponsored/drawing-kit.png",
        "contentType": "PRODUCT",
        "isSponsored": true,
        "disclosureLabel": "광고·제휴",
        "externalUrl": "https://partner.example.com/products/drawing-kit",
        "sortOrder": 1
      }
    ]
  }
}
```

- 실제 제휴사(쿠팡파트너스) 연동은 승인 전까지 보류한다. 이번 범위는 테이블·조회
  로직까지이며, 콘텐츠는 샘플 데이터로 시작한다(2026-11-04 팀 회의 결정). 승인이
  나면 `sponsored_contents` 행을 실제 제휴 상품 데이터로 교체한다.
- `sponsored_contents`(콘텐츠 자체) · `activity_sponsored_contents`(활동-콘텐츠
  매핑 + `sortOrder`) 테이블을 신규로 추가했다(V6). 같은 콘텐츠가 활동마다 다른
  순서를 가질 수 있도록 `sortOrder`는 매핑 테이블에 둔다.
- 노출 조건: `status='ACTIVE'`이고 `starts_at`/`ends_at` 노출 기간 안(둘 다 NULL이면
  상시 노출)인 콘텐츠만 `sortOrder` 오름차순으로 반환한다. 조건을 만족하는 콘텐츠가
  없으면 빈 배열을 반환한다.
- `activityId`가 존재하지 않거나 `PUBLISHED`가 아니면 `ACTIVITY404_1`을 반환한다 —
  API-31(활동 저장)의 `ActivityErrorCode.NOT_SAVABLE`을 그대로 재사용했다(같은
  코드를 공유하며 메시지 문구는 기존 걸 그대로 씀).
- `externalUrl`은 서버에 등록한 허용된 HTTPS 주소만 저장한다 — DB
  `CHECK (external_url LIKE 'https://%')`로 한 번 더 강제한다.
- `AuthFilter`의 공통 상태 검사는 `PENDING_ONBOARDING`을 통과시키므로, 다른
  보호 API와 동일하게 이 서비스에서도 별도로 `AUTH403_2`를 체크한다. 이 API는
  단순 조회라 락 없이 `findById`만 쓴다(TOCTOU 재검증이 필요한 변경 작업이 아님).
- `contentType`은 `PRODUCT`/`CLASS`/`ARTICLE` 세 값만 허용한다.
- 범위 밖: 클릭 집계·전환 정산 API(원본 49개 목록에 없음), 콘텐츠 생성/수정 API
  (운영자가 DB에 직접 등록).
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_3`, `AUTH403_2`,
  `ACTIVITY404_1`, `COMMON429_1`, `COMMON500_1`. 호출 제한은 미구현.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`SponsoredContentIntegrationTests`에 검증 테스트를 포함한다(노출 조건을 만족하는
콘텐츠만 sortOrder 순으로 반환 / 콘텐츠 없을 때 빈 배열 / 존재하지 않거나 비공개인
활동 404 / 인증·온보딩 에러).
