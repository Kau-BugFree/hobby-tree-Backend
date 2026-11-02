# 회원 탈퇴

`API.md`의 API-07 명세를 따른다. `/api/v1/users/me`의 조회·수정은
[내 프로필 조회](my-profile.md), [내 프로필 수정](profile-edit.md) 참고.

## 구현 범위

```http
DELETE /api/v1/users/me
Authorization: Bearer {accessToken}
```

Request Body는 없다. 성공은 HTTP 200 / `USER200_3`다.

```json
{
  "isSuccess": true,
  "code": "USER200_3",
  "message": "회원 탈퇴가 처리되었습니다.",
  "result": {
    "userId": 1,
    "memberStatus": "WITHDRAWN",
    "withdrawnAt": "2026-10-29T06:00:00Z",
    "restorableUntil": "2026-11-28T06:00:00Z"
  }
}
```

- `withdrawnAt`/`restorableUntil`은 `Instant` 직렬화 결과로 UTC(`Z`) 형식이다. 클라이언트는
  문자열을 직접 비교하지 말고 파싱 후 비교하거나 로컬 타임존으로 변환해서 표시한다.
- `AuthFilter`가 Access Token을 검증하여 `userId`를 식별한다(기존 보호 API와 동일).
  `/api/v1/users/me`는 `SecurityConfig`의 `permitAll` 목록에 없으므로 인증이 필수다.
- `account_status`를 탈퇴 전 상태(`status_before_withdrawal`)에 보관하고 `WITHDRAWN`으로 전환한다.
  `withdrawn_at`은 처리 시각, `restorable_until`은 `withdrawn_at + 720시간`(30일)이다.
- 처리와 같은 트랜잭션에서 잠금을 다시 확인한다. `AuthFilter`의 잠금 없는 상태 확인과 이 시점 사이에
  계정 상태가 바뀌었다면(동시 탈퇴 요청 등) 409 / `USER409_1`이다.
- 성공 시 Redis `auth:session:{userId}`(현재 로그인 세션)와 `auth:restore:{userId}`(이전 복구 토큰)를
  모두 폐기한다. 폐기된 세션의 Access/Refresh Token은 즉시 사용할 수 없다.
- `AuthFilter`의 보호 API 공통 상태 검사는 `WITHDRAWN`을 403 / `AUTH403_3`으로 구분한다
  (이전에는 `WITHDRAWN` 계정이 실제로 존재할 일이 없어 일반 401 / `AUTH401_2`에 묶여 있었다).
- 탈퇴 후 30일 경과 시 개인정보 익명화·정리 작업과 외부 예약 취소/환불 자동화는 이번 범위에 포함하지 않는다.
- 호출 제한 수치는 아직 미확정이며 로그인 API의 IP 제한을 적용하지 않는다.

## 관련 API

- 탈퇴한 계정은 로그인 시 복구 안내와 복구 토큰을 받는다. [소셜 로그인](social-login.md)의 "상태별 응답과 호출 제한" 참고.
- 복구는 `POST /api/v1/auth/restore`. [소셜 로그인](social-login.md)의 "계정 복구" 참고.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

`AuthIntegrationTests`에 탈퇴 테스트를 포함한다(정상 탈퇴 후 세션 폐기·복구 전체 여정,
세션이 남은 상태에서의 403, 동시 탈퇴 요청). 별도 테스트 클래스를 추가하지 않은 이유는
CI(`scripts/ci/auth-test-entrypoint.sh`)가 `*AuthIntegrationTests` 하나만 실행하도록
고정되어 있기 때문이다.
