# 클래스 오픈 알림 신청

`API.md`의 API-40 명세를 따른다.

## 구현 범위

```http
POST /api/v1/experiences/{experienceId}/open-notification
Authorization: Bearer {accessToken}
```

Request Body는 없다.

```json
{
  "isSuccess": true,
  "code": "SUBSCRIPTION201_1",
  "message": "클래스 오픈 알림이 신청되었습니다.",
  "result": {
    "experienceId": 602,
    "isSubscribed": true,
    "channel": "IN_APP",
    "subscribedAt": "2026-10-29T15:00:00Z"
  }
}
```

이미 신청한 상태라면 동일한 `result`를 200 / `SUBSCRIPTION200_1`로 반환한다(새로 만들지
않고 기존 구독을 그대로 돌려준다).

- `experiences.listing_status`가 `COMING_SOON`인 경우만 신청을 받는다. 그 외
  (`DRAFT`/`OPEN`/`CLOSED`/`CANCELLED`)는 `SUBSCRIPTION409_1`("이미 오픈했거나 오픈
  알림 신청 대상이 아님")을 반환한다.
- `COMING_SOON`은 이번 작업에서 새로 추가한 상태값이다(V5 마이그레이션). 기존 `DRAFT`는
  "비공개 작성 중"이라는 의미로 이미 쓰이고 있어서, "공개됐지만 아직 예약은 안 열린 상태"를
  그대로 재사용하면 추후 클래스 목록 API(API-35/36)가 `DRAFT`를 숨길 때 알림 신청 대상까지
  같이 숨어버리는 문제가 생긴다. 두 의미를 분리하기 위해 새 상태값을 추가했다.
- 존재하지 않는 `experienceId`는 `EXPERIENCE404_1`을 반환한다.
- `notification_subscriptions`에 `(user_id, experience_id, CLASS_OPEN, unsubscribed_at
  IS NULL)` 구독이 있으면 그 결과를 반환하고, 없으면 새로 만든다. 부분 유니크 인덱스
  `uq_sub_class`와 일치시켰다.
- 같은 사용자의 동시 요청은 API-47(채팅 출시 알림 신청)과 동일하게 사용자 행을 잠궈
  직렬화한다.
- `AuthFilter`의 공통 상태 검사는 `PENDING_ONBOARDING`을 통과시키므로, API-47과 동일하게
  이 서비스에서도 별도로 `AUTH403_2`를 체크한다. 잠금 획득 후 `SUSPENDED`/`WITHDRAWN`도
  다시 확인한다.
- 범위 밖: 구독 취소 API, 클래스가 실제 `OPEN`으로 바뀔 때 발송 처리 로직, 클래스
  목록·상세 조회 API(API-35~39, 아직 미구현).
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_3`, `AUTH403_2`,
  `EXPERIENCE404_1`(신규), `SUBSCRIPTION409_1`(신규), `COMMON429_1`, `COMMON500_1`.
  호출 제한은 미구현.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`ExperienceIntegrationTests`에 검증 테스트를 포함한다(최초 신청 201, 재신청 200,
`COMING_SOON`이 아닌 상태 거절, 존재하지 않는 experienceId 404, 동시 신청 직렬화).
