# 알림 목록

`API.md`의 API-44 명세를 따른다.

## 구현 범위

```http
GET /api/v1/notifications?isRead=false&type=TREE_GROWTH&cursor=...&size=20
Authorization: Bearer {accessToken}
```

모든 쿼리 파라미터는 선택이다.

```json
{
  "isSuccess": true,
  "code": "NOTIFICATION200_1",
  "message": "알림 목록을 조회했습니다.",
  "result": {
    "items": [
      {
        "notificationId": 10001,
        "type": "TREE_GROWTH",
        "title": "드로잉 나무가 자랐어요!",
        "body": "새싹에서 묘목 단계가 되었어요. 다음 활동을 확인해 보세요.",
        "target": { "type": "USER_HOBBY", "id": 201 },
        "isRead": false,
        "readAt": null,
        "createdAt": "2026-10-29T06:20:00Z"
      }
    ],
    "nextCursor": null,
    "hasNext": false,
    "unreadCount": 1
  }
}
```

- `isRead`: `true`/`false`. 생략 시 읽음 여부 전체.
- `type`: `ACTIVITY_REMINDER`/`TREE_GROWTH`/`EXPERIENCE_OPEN`/`ANNOUNCEMENT`/`CHAT_LAUNCH` 중 하나.
  다른 값은 400 / `COMMON400_1`.
- `size`: 기본 20, 1~50. 범위 밖이면 400.
- `cursor`: 서버가 발급한 opaque 문자열(`base64(createdAt.toString()_notificationId)`).
  `created_at`은 PostgreSQL에서 마이크로초 정밀도라, epoch millis로 자르면 커서 시각과
  실제 시각 사이의 알림이 다음 페이지에서 누락될 수 있어 `Instant` 전체 정밀도를 그대로 쓴다.
  이 레포 최초의 커서 페이지네이션 구현이다. 생성 시각·알림 ID 내림차순 keyset
  페이지네이션이며, 잘못된 형식은 400.
- `target`: `relatedUserHobby`/`relatedActivity` 중 있는 쪽을 반환한다(`USER_HOBBY`/`ACTIVITY`).
  둘 다 없으면 `null` — 현재 엔티티에 `EXPERIENCE`/`ANNOUNCEMENT`/`CHAT_LAUNCH` 대상을 가리킬
  연관관계가 없어서, 이 유형들의 알림은 항상 `target: null`이다.
- `unreadCount`: 필터·페이지와 무관한 회원 전체의 미읽음 수.
- **신규**: `AUTH403_2`(온보딩 미완료 회원)로 `PENDING_ONBOARDING`을 막는다. `AuthFilter`의
  공통 상태 검사는 그대로 두고(여전히 `PENDING_ONBOARDING`을 통과시켜야 `/users/me`가
  동작한다), 이 서비스에서만 별도로 체크한다.
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_2`(신규), `AUTH403_3`,
  `COMMON429_1`, `COMMON500_1`. 호출 제한은 미구현.

## 범위 밖

알림을 실제로 생성하는 트리거(활동 완료, 나무 성장 등)와 읽음 처리(API-45)는 포함하지
않는다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`AuthIntegrationTests`에 조회 테스트를 포함한다(읽음/유형 필터와 사용자 격리, 커서
페이지네이션, 동일 `createdAt` 경계에서의 `notificationId` 내림차순 정렬, `PENDING_ONBOARDING`
거절, 잘못된 쿼리 파라미터 거절). 별도 테스트 클래스를 추가하지 않은 이유는
[member-withdrawal.md](member-withdrawal.md)와 동일하다.

## 구현 중 발견한 버그

PostgreSQL은 `IS NULL`에만 쓰이는 바인드 파라미터의 타입을 추론하지 못한다
("could not determine data type of parameter"). 특히 `Instant` 커서 값이 그랬다.
그래서 "값이 있는지"를 별도의 `boolean` 파라미터(`hasType`/`hasIsRead`/`hasCursor`)로
분리하고, 실제 값 파라미터는 항상 null이 아니게(미사용 시 더미값) 넘긴다 —
`UserNotificationRepository.findPage` 참고.

3. **커서 정밀도 손실(리뷰로 발견)**: `encodeCursor`가 `toEpochMilli()`를 써서 PostgreSQL의
   마이크로초 정밀도를 밀리초로 잘랐다. `createdAt < cursor` 비교에서 잘린 시각과 실제
   시각 사이에 생성된 알림이 다음 페이지에서 누락될 수 있었다. `Instant.toString()`/
   `Instant.parse()`로 전체 정밀도를 보존하도록 수정했다.
