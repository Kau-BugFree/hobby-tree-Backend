# 내 프로필 수정

`API.md`의 API-06 명세를 따른다. `/api/v1/users/me`에 이미 `GET`([내 프로필 조회](my-profile.md))과
`DELETE`([회원 탈퇴](member-withdrawal.md))가 있어 같은 `UserController`에 추가한다.

## 구현 범위

```http
PATCH /api/v1/users/me
Authorization: Bearer {accessToken}
Content-Type: application/json
```

```json
{
  "nickname": "즐거운수달",
  "ageRange": "TWENTIES"
}
```

두 필드 모두 생략 가능하지만 **최소 한 개는 보내야 한다**. 보낸 필드만 바꾸고 생략한
필드는 그대로 둔다. 둘 다 없는 빈 `{}` 본문은 아무것도 바꾸지 않으므로 400 /
`COMMON400_1`로 거부한다.

성공은 HTTP 200 / `USER200_2`.

```json
{
  "isSuccess": true,
  "code": "USER200_2",
  "message": "내 프로필이 수정되었습니다.",
  "result": {
    "userId": 1,
    "nickname": "즐거운수달",
    "profileImageUrl": null,
    "ageRange": "TWENTIES",
    "updatedAt": "2026-10-29T06:00:00Z"
  }
}
```

- `nickname`: 앞뒤 공백 제거 후 2~12자, 한글·영문·숫자·밑줄만 허용. 제어문자·비문자열
  JSON 값(숫자·불리언 등)은 400 / `COMMON400_1`.
- `ageRange`: `TEENS`/`TWENTIES`/`THIRTIES`/`FORTIES`/`FIFTIES_PLUS`/`UNSPECIFIED` 중 하나만
  허용. 다른 문자열이나 비문자열 JSON 값은 400 / `COMMON400_1`.
- `updatedAt`은 DB가 생성하는 `users.updated_at`이다. 응답을 만들기 전에 flush해서
  이번 요청으로 갱신된 최신 값을 반환한다.
- 에러코드: `COMMON400_1`, `AUTH401_2`, `AUTH403_1`, `AUTH403_3`(전부 기존 코드 재사용).

## 범위 밖 — profileImageKey

명세의 `profileImageKey`는 **이번 PR에 포함하지 않는다**. API-08(프로필 이미지 업로드
URL 발급)이 발급한, 본인 소유 키인지 검증해야 하는데 API-08이 아직 없다. 요청에
`profileImageKey`를 보내도 `UpdateProfileRequest`에 해당 필드가 없어 조용히 무시된다
(명시적인 400 거절은 아니다). API-08 구현 시 함께 추가한다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`UserIntegrationTests`에 수정 테스트를 포함한다(부분 수정·trim 확인, 빈 본문 no-op,
잘못된 `nickname`/`ageRange` 입력 거절).
