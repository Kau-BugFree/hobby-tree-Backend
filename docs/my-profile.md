# 내 프로필 조회

`API.md`의 API-05 명세를 따른다. `/api/v1/users/me`는 이미 `PATCH`([내 프로필 수정](profile-edit.md))와
`DELETE`([회원 탈퇴](member-withdrawal.md))가 있어 같은 `UserController`에 추가한다.

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
      "notificationFrequency": "NONE"
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

명세의 `location`·`locationConsent`는 아직 포함하지 않는다:

- **`location`**, **`locationConsent`**: 명세가 기대하는 `regionId`/`level`/`parentRegionId`
  구조의 지역 참조 테이블(`regions`)은 `API.md` 자신도 "(신규 제안)"으로 표시하며
  "regionId를 외부 행정구역 코드로 할지는 데이터 연동 설계에서 확정한다"고 명시한다.
  API-41/42/43(지역 검색·설정) 작업에서 확정될 내용이라 이번 응답에는 두 필드 모두
  **포함하지 않는다**(키 자체가 응답에 없다).

알림 설정은 UI 기준으로 저장된 수신 빈도(`NONE`, `WEEKLY`, `ACTIVITY_DAYS`)를
반환한다. 취향 설정 행이 없으면 `NONE`을 반환한다.
변경 API와 응답 구조는 [notification-settings.md](notification-settings.md)를 참고한다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`UserIntegrationTests`에 조회 테스트를 포함한다(신규 회원 기본값, `ACTIVE` 전환 시
`onboardingCompleted`, `ACTIVE`/`PAUSED`만 집계하는 `treeCount`).
