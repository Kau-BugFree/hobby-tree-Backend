# 내 프로필 조회

`API.md`의 API-05 명세를 따른다. `/api/v1/users/me`는 이미 `DELETE`(회원 탈퇴,
[member-withdrawal.md](member-withdrawal.md))가 있어 같은 `UserController`에 추가한다.

## 구현 범위

```http
GET /api/v1/users/me
Authorization: Bearer {accessToken}
```

Request Body는 없다. 성공은 HTTP 200 / `USER200_1`.

```json
{
  "isSuccess": true,
  "code": "USER200_1",
  "message": "내 프로필을 조회했습니다.",
  "result": {
    "userId": 1,
    "nickname": null,
    "profileImageUrl": null,
    "provider": "KAKAO",
    "ageRange": null,
    "memberStatus": "PENDING_ONBOARDING",
    "onboardingCompleted": false,
    "treeCount": 0,
    "maxTreeCount": 5,
    "notificationSettings": {
      "activityReminderEnabled": true,
      "treeGrowthEnabled": true,
      "experienceOpenEnabled": true,
      "announcementEnabled": true,
      "quietHours": {
        "enabled": false,
        "start": "22:00",
        "end": "08:00",
        "timeZone": "Asia/Seoul"
      }
    },
    "createdAt": "2026-10-01T03:00:00Z"
  }
}
```

- `onboardingCompleted`은 `account_status != PENDING_ONBOARDING`으로 판단한다
  (`AuthFilter`가 이미 `ACTIVE`/`PENDING_ONBOARDING`만 통과시키므로 둘 중 하나다).
- `treeCount`는 `user_hobbies`에서 `tree_status IN ('ACTIVE','PAUSED')`인 행만 센다.
  `ENDED`는 포함하지 않는다 — API-19(취미 나무 심기)의 "ACTIVE+PAUSED 나무가 5그루를
  넘지 않게 한다"는 규칙과 일치시킨 것이다. `maxTreeCount`는 같은 정책의 고정값 5다.
- `profileImageUrl`은 `profile_image_key`가 없으면 `null`이다. 업로드 API(API-08/09)가
  아직 없어서 현재는 모든 회원이 `null`이다. 키가 생겨도 키→URL 변환 로직은 아직 없다.

## 범위 밖 — 구현 전 확인 필요

명세의 `notificationSettings`·`location`·`locationConsent` 중 다음은 이번 PR에
포함하지 않았다:

- **`location`**, **`locationConsent`**: 명세가 기대하는 `regionId`/`level`/`parentRegionId`
  구조의 지역 참조 테이블(`regions`)은 `API.md` 자신도 "(신규 제안)"으로 표시하며
  "regionId를 외부 행정구역 코드로 할지는 데이터 연동 설계에서 확정한다"고 명시한다.
  API-41/42/43(지역 검색·설정) 작업에서 확정될 내용이라 이번 응답에는 두 필드 모두
  **포함하지 않는다**(키 자체가 응답에 없다).
- **`notificationSettings`**은 포함하지만, 저장 테이블이 없어 모든 회원에게
  API.md 예시와 동일한 고정 기본값을 반환한다. API-46(알림 설정 변경)이 구현되기 전까지는
  실제로 변경·저장되지 않는다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`AuthIntegrationTests`에 조회 테스트를 포함한다(신규 회원 기본값, `ACTIVE` 전환 시
`onboardingCompleted`, `ACTIVE`/`PAUSED`만 집계하는 `treeCount`). 별도 테스트 클래스를
추가하지 않은 이유는 [member-withdrawal.md](member-withdrawal.md)와 동일하다 — CI가
`*AuthIntegrationTests` 하나만 실행한다.
