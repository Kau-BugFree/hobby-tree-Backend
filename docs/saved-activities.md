# 활동 저장 (API-31)

UI.zip의 UI-이미지-35.jpg ‘저장하기’ 메뉴에 대응한다.

`PUT /api/v1/activities/{activityId}/save`

- Authorization: Bearer {accessToken}
- activityId: 양의 정수 (Long 범위)
- 요청 본문 없음
- 온보딩을 완료한 ACTIVE 회원만 사용 가능

HTTP 200 응답 예시:

```json
{
  "isSuccess": true,
  "code": "SAVED200_1",
  "message": "활동이 저장되었습니다.",
  "result": {
    "activityId": 1003,
    "isSaved": true,
    "savedAt": "2026-11-03T00:00:00Z"
  }
}
```

최초 저장과 재요청 모두 200이다. 재요청은 최초 savedAt을 유지한다.
같은 사용자의 동시 요청도 하나의 저장 관계와 같은 저장 시각을 반환한다.
다른 사용자는 같은 활동을 각각 저장할 수 있다.

활동의 review_status가 PUBLISHED일 때만 저장할 수 있다.
이미 저장한 활동이 비공개로 변경된 경우에도 새 저장 요청은 404이며 기존 저장 관계는 유지한다.
활동 저장은 나무 보유 여부와 무관하며 나무 생성·활동 시작·성장 처리를 하지 않는다.

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | COMMON400_1 | 활동 ID 형식·범위 오류 |
| 401 | AUTH401_2 | Access Token 누락·만료·위조·폐기 |
| 403 | AUTH403_1 | 정지 회원 |
| 403 | AUTH403_2 | 온보딩 미완료 |
| 403 | AUTH403_3 | 탈퇴 회원 |
| 404 | ACTIVITY404_1 | 활동 없음 또는 비공개 |

기존 activities와 saved_activities 테이블을 재사용하며 마이그레이션은 없다.
사용자 행 잠금으로 같은 사용자의 저장을 순차 처리한다.
활동 행에는 읽기 잠금을 걸어 공개 상태 검증부터 저장 완료까지 상태 변경을 방지한다.

ActivityIntegrationTests에서 실제 PostgreSQL·Redis와 HTTP 요청으로 검증한다.
외부 소셜 토큰 검증만 테스트 대역을 사용한다.
저장 목록(API-33), 실제 프론트 앱 동작은 이번 범위에 포함하지 않는다.

## 활동 저장 취소 (API-32)

`DELETE /api/v1/activities/{activityId}/save`

저장 API와 동일한 인증 헤더와 양의 정수 activityId를 사용하며 요청 본문은 없다.
온보딩을 완료한 ACTIVE 회원만 사용할 수 있다.

HTTP 200 응답:

```json
{
  "isSuccess": true,
  "code": "SAVED200_2",
  "message": "활동 저장이 취소되었습니다.",
  "result": {"activityId": 1003, "isSaved": false}
}
```

- 본인의 저장 관계만 삭제한다. 원본 활동, 다른 회원의 저장, 나무·활동 기록은 삭제하지 않는다.
- 미저장·반복 취소·원본 비공개·삭제·존재하지 않는 활동 ID 모두 200을 반환한다.
- 응답에는 savedAt이 없으며 Cache-Control: no-store를 적용한다.
- 저장과 동일한 사용자 행 잠금을 사용한다. 동시 저장·취소의 최종 상태는 DB 처리 순서를 따른다.
- 활동 ID 오류는 COMMON400_1, 인증 오류는 AUTH401_2, 계정 상태 오류는
  AUTH403_1 / AUTH403_2 / AUTH403_3이다. 원본 활동에 대한 404 검사는 수행하지 않는다.
- 기존 saved_activities를 재사용하며 마이그레이션은 없다.

ActivityIntegrationTests에 정상·반복 취소, 사용자 분리, 비공개·삭제 활동,
저장·취소 동시 요청, 인증·계정 상태·입력 검증을 포함한다.
