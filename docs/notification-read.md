# 알림 읽음 처리

`API.md`의 API-45 명세를 따른다.

## 구현 범위

```http
PATCH /api/v1/notifications/{notificationId}/read
Authorization: Bearer {accessToken}
```

Request Body는 없다.

```json
{
  "isSuccess": true,
  "code": "NOTIFICATION200_2",
  "message": "알림이 읽음 처리되었습니다.",
  "result": {
    "notificationId": 10001,
    "isRead": true,
    "readAt": "2026-10-29T15:20:00Z",
    "unreadCount": 0
  }
}
```

- 본인 소유 알림인지 `user_notifications.user_id`로 확인한다. 본인 소유가 아니거나 존재하지
  않으면 404 / `NOTIFICATION404_1`.
- 멱등 처리: 이미 읽은 알림에 다시 요청해도 최초 `readAt`을 유지하고, `unreadCount`를
  중복 차감하지 않는다.
- `unreadCount`: API-44와 동일하게 필터·페이지와 무관한 회원 전체의 미읽음 수.
- `notificationId` 경로 파라미터가 정수가 아니면 400 / `COMMON400_1` (Spring의 타입 불일치
  처리가 공통 핸들러로 떨어진다).
- **신규**: API-44와 동일하게 `AuthFilter`의 공통 상태 검사는 `PENDING_ONBOARDING`을 통과시키므로,
  이 서비스에서도 별도로 `AUTH403_2`를 체크한다.
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_2`, `AUTH403_3`,
  `NOTIFICATION404_1`(신규), `COMMON429_1`, `COMMON500_1`. 호출 제한은 미구현.

## 범위 밖

알림을 실제로 생성하는 트리거와 전체 읽음 처리(일괄 읽음)는 포함하지 않는다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`AuthIntegrationTests`에 검증 테스트를 포함한다(정상 읽음 처리와 멱등성, 타인 소유 알림
404, `PENDING_ONBOARDING` 거절, 정수가 아닌 경로 파라미터 거절). 별도 테스트 클래스를
추가하지 않은 이유는 [member-withdrawal.md](member-withdrawal.md)와 동일하다.

## 구현 중 발견한 버그

`Clock.instant()`는 나노초 정밀도까지 반환하지만, `read_at`은 PostgreSQL에서 마이크로초
정밀도로 저장된다. 읽음 처리 응답을 (DB에 다시 flush/refresh 없이) 메모리에 있는 엔티티
값으로 그대로 만들면, 첫 응답의 `readAt`이 실제로 저장된 값보다 더 정밀해서 같은 알림을
나중에 다시 조회했을 때와 값이 달라질 수 있었다(멱등성 테스트에서 실제로 재현·발견).
`clock.instant().truncatedTo(ChronoUnit.MICROS)`로 저장 정밀도에 맞춰 잘라서 해결했다.
