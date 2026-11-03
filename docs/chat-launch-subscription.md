# 채팅 출시 알림 신청

`API.md`의 API-47 명세를 따른다.

## 구현 범위

```http
POST /api/v1/notification-subscriptions/chat-launch
Authorization: Bearer {accessToken}
```

Request Body는 없다.

```json
{
  "isSuccess": true,
  "code": "SUBSCRIPTION201_2",
  "message": "채팅 출시 알림이 신청되었습니다.",
  "result": {
    "subscriptionType": "CHAT_LAUNCH",
    "isSubscribed": true,
    "channel": "IN_APP",
    "subscribedAt": "2026-10-29T15:00:00Z"
  }
}
```

이미 신청한 상태라면 동일한 `result`를 200 / `SUBSCRIPTION200_2`로 반환한다(새로 만들지
않고 기존 구독을 그대로 돌려준다).

- `notification_subscriptions`에 `(user_id, CHAT_LAUNCH, unsubscribed_at IS NULL)` 구독이
  있으면 그 결과를 반환하고, 없으면 새로 만든다. 부분 유니크 인덱스 `uq_sub_chat`과
  일치시켰다.
- 같은 사용자의 동시 요청은 `NotificationService.markRead`와 동일하게 사용자 행을 잠궈
  직렬화한다 — 두 요청이 모두 "구독 없음"을 보고 동시에 insert를 시도해 유니크 인덱스
  충돌로 떨어지는 것을 막는다.
- `AuthFilter`의 공통 상태 검사는 `PENDING_ONBOARDING`을 통과시키므로, API-44/45와
  동일하게 이 서비스에서도 별도로 `AUTH403_2`를 체크한다. 잠금 획득 후 `SUSPENDED`/
  `WITHDRAWN`도 다시 확인한다(API-45의 TOCTOU 리뷰와 동일한 이유).
- `SUBSCRIPTION409_2`("이미 출시되었거나 신청 기간이 종료됨")는 아직 "출시 여부"를
  나타내는 테이블·플래그가 없어서, `notification.notification-subscriptions.chat-launch.closed`
  (기본값 `false`) 설정값으로 구현했다. 운영 정책이 더 복잡해지면(특정 일시 기준 등) 다시
  설계한다.
- `experience_id`는 항상 `null`이다 — `CHECK` 제약(`CLASS_OPEN`일 때만 필수)과 일치.
- 범위 밖: 구독 취소 API, 실제 출시 시 알림 발송 로직.
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_3`, `AUTH403_2`,
  `SUBSCRIPTION409_2`(신규), `COMMON429_1`, `COMMON500_1`. 호출 제한은 미구현.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`NotificationIntegrationTests`에 검증 테스트를 포함한다(최초 신청 201, 재신청 200,
동시 신청 직렬화, `PENDING_ONBOARDING` 거절, 출시 종료 시 409).
