# 알림 수신 빈도 설정 (API-46)

UI.zip의 `UI-이미지-8.jpg`를 기준으로 한다. 기존 API.md의 종류별 수신 여부와
방해 금지 시간 대신 UI의 수신 빈도를 저장한다.

`PATCH /api/v1/users/me/notification-settings`

서비스 Access Token을 `Authorization: Bearer {accessToken}`으로 전달한다.
`Content-Type: application/json`을 사용한다.

```json
{"notificationFrequency":"WEEKLY"}
```

| 값 | UI 표시 |
| --- | --- |
| NONE | 받지 않기 (기본값) |
| WEEKLY | 주 1회 요약 |
| ACTIVITY_DAYS | 활동한 날만 |

`notificationFrequency`는 필수 문자열이며 위 값만 허용한다. 누락, null,
잘못된 타입과 값은 HTTP 400 / COMMON400_1로 처리한다.

HTTP 200 응답 예시:

```json
{
  "isSuccess": true,
  "code": "NOTIFICATION200_3",
  "message": "알림 설정이 변경되었습니다.",
  "result": {
    "userId": 1,
    "notificationSettings": {"notificationFrequency": "WEEKLY"},
    "updatedAt": "2026-11-03T00:00:00Z"
  }
}
```

내 프로필 조회의 `result.notificationSettings`도 같은 구조를 사용한다.
기존 boolean 필드와 quietHours는 제거되므로 프론트는 새 구조를 사용해야 한다.

기존 `user_preferences.notification_frequency`를 재사용하여 스키마 변경은 없다.
설정 행이 없으면 조회 시 NONE을 반환하고, 첫 변경 시 기본 취향 설정 행을 만든다.
이 행의 생성은 온보딩 완료를 의미하지 않으며 회원 상태를 변경하지 않는다.
기존 취향 설정이 있으면 다른 필드는 유지한다. 같은 회원의 변경 요청은 사용자 행 잠금으로
순차 처리한다. 추후 취향 저장 API도 같은 잠금 규칙을 사용해야 한다.

미인증 요청은 401, 정지·탈퇴 회원은 403이다. 온보딩 전 회원도 설정할 수 있다.
실제 푸시 발송, 주간 스케줄링, 활동일 판단 및 알림 생성 로직은 이번 구현에 포함하지 않는다.

검증: `AUTH_INTEGRATION_TEST=true`로 `gradlew.bat spotlessApply build test check`를 실행한다.
실제 PostgreSQL/Redis를 사용하는 검증은 `NotificationIntegrationTests`에 포함한다.
