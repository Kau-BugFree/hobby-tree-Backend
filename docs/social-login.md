# 소셜 로그인

EC2 운영 설정은 [인증·Redis 배포](auth-redis-ec2-deployment.md)를 따른다. 업무 로직 변경 없이 운영 Secret 전달과 CI 실행을 보완한다.

## 구현 범위

`POST /api/v1/auth/social-login`과 `POST /api/v1/auth/reissue`를 구현한다. 로그아웃, 계정 복구 및
탈퇴 후 개인정보 정리 작업은 별도 API/작업으로 구현해야 한다.
이번 로그인에서는 탈퇴 회원의 복구 안내와 복구 토큰 발급까지만 제공한다.

```json
{
  "provider": "KAKAO",
  "providerToken": "SDK에서 받은 Access Token"
}
```

- `KAKAO`: 카카오 Access Token. 앱 ID 및 만료를 확인한 뒤 사용자 정보를 조회한다.
- `GOOGLE`: 구글 ID Token. 공개키 서명, 발급자, 대상 Client ID, 만료를 검증한다.
- 검증한 제공자와 소셜 식별자로 회원을 찾으며 이메일로 계정을 연결하지 않는다.
- 신규 회원은 `PENDING_ONBOARDING`, 기존 회원은 현재 상태로 응답한다.
- 성공은 HTTP 200 / `AUTH200_1`이며 기존 공통 `ApiResponse` 형식을 사용한다.

## 환경 설정과 실행

`.env.example`을 참고하여 `.env`를 작성한다. 실제 값은 Git에 포함하지 않는다.
`KAKAO_APP_ID`는 숫자 앱 ID이고 `GOOGLE_CLIENT_ID`는 프론트의
`webClientId`/`serverClientId`와 일치하는 웹 유형 Client ID다.
`JWT_SECRET`은 최소 64바이트의 암호학적 랜덤 키를 Base64로 인코딩한 값이다.

```powershell
docker compose up -d postgres redis
.\scripts\run-local.ps1
```

## 토큰과 세션

- 하비트리 JWT는 HS256으로 서명하고 소셜 토큰과 구분한다.
- Access Token 30분, Refresh Token 14일. JWT에는 사용자 ID, 세션 ID, 토큰 용도,
  발급자, 대상 서비스, 발급/만료 시각, 토큰 ID를 포함한다.
- Redis `auth:session:{userId}`에 현재 세션 ID, Refresh Token의 SHA-256 해시,
  만료 시각을 저장하고 TTL을 14일로 설정한다.
- 로그인 성공 시 사용자별 세션을 교체한다. 보호된 요청마다 세션 ID와 회원 상태를
  검증하여 이전 기기의 Access Token을 차단한다. 이미 처리 중인 요청을 취소하지는 않는다.
- Refresh Token과 복구 토큰은 일반 API 인증에 사용할 수 없다.
- Redis 장애 시 검증을 우회하지 않고 500을 반환한다. 세션 데이터 유실 시 재로그인이 필요하다.
- 재발급 시 Refresh Token을 교체하고 14일 만료를 연장한다. 사용된 토큰은 기존
  만료 시각까지 해시 이력으로 보관하여 재사용을 탐지한다.

## 토큰 재발급

```http
POST /api/v1/auth/reissue
Content-Type: application/json
```

```json
{
  "refreshToken": "하비트리 Refresh Token"
}
```

- Authorization 헤더는 필요 없다. 만료된 Access Token이 헤더에 있어도 본문의
  Refresh Token을 기준으로 검증한다. 성공은 HTTP 200 / `AUTH200_2`다.
- 응답은 로그인과 같은 토큰 필드를 포함하되 `isNewUser`는 포함하지 않는다.
  프론트는 받은 Access Token·Refresh Token을 함께 교체한다.
- 서명·발급자·대상·만료·토큰 용도·사용자·세션을 검증한다. Access Token이나
  복구 토큰은 재발급에 사용할 수 없다. 불일치·만료·폐기·재사용은 401 / `AUTH401_3`이다.
- Redis Lua 스크립트로 현재 세션 확인, 사용 이력 기록, 토큰 해시 교체, TTL 갱신을
  원자적으로 처리한다. 사용 이력 키는 `auth:refresh-used:{userId}:{sid}:{tokenHash}`다.
  토큰 원문은 저장하지 않고 기존 토큰의 만료 시각에 이력도 만료된다.
- 세션 ID는 재발급 중 유지한다. 이미 발급된 Access Token은 자신의 30분 만료까지
  유효하며, 새 로그인·세션 폐기·재사용 탐지 시에는 즉시 차단된다.
- 같은 세션의 사용된 Refresh Token이 다시 오면 해당 세션을 폐기한다. 이전 기기의
  토큰은 새 로그인 세션을 폐기할 수 없다.
- 프론트는 재발급 요청을 단일 실행으로 묶어야 한다. 동시에 같은 토큰으로 재발급하면
  하나만 성공하고 나머지는 재사용으로 탐지되어 성공 응답의 토큰도 무효화될 수 있다.
  네트워크 응답 유실 후 같은 토큰을 자동 재전송하는 경우도 동일하다.
- `SUSPENDED`는 403 / `AUTH403_1`, `WITHDRAWN`은 403 / `AUTH403_3`이다.
- 재발급 API의 호출 제한 수치는 아직 미확정이며 로그인 API의 IP 제한을 적용하지 않는다.

## 상태별 응답과 호출 제한

- `SUSPENDED`: HTTP 403 / `AUTH403_1`.
- `WITHDRAWN`: HTTP 409 / `AUTH409_1`. 30일 이내이면 최대 5분 유효한 복구 전용
  토큰을 반환한다. 유효기간은 계정 복구 기한을 넘지 않는다.
- Redis `auth:restore:{userId}`에 복구 토큰 해시를 저장한다. 재로그인으로 복구 토큰을
  재발급하면 이전 복구 토큰은 무효다. 복구 API는 토큰의 사용자 ID 부분으로 저장소를
  조회하고 해시·TTL·계정 상태를 검증한 뒤 원자적으로 소비하도록 구현해야 한다.
- 로그인은 실제 연결 IP당 첫 요청부터 60초 동안 20회까지 허용한다. 잘못된 JSON과
  검증 실패 요청도 집계하며 초과 시 429 / `COMMON429_1`, `Retry-After`를 반환한다.
- 현재는 전달 헤더를 신뢰하지 않는다(`server.forward-headers-strategy: none`).
  ALB 등 프록시 배포 전에는 신뢰할 프록시 범위를 설정하여 실제 클라이언트 IP를
  안전하게 복원해야 한다. 그대로 배포하면 프록시 IP를 공유하여 제한될 수 있다.
- 로그인과 Swagger, 오류 처리를 제외한 경로는 인증을 요구한다. 향후 공개 API는
  확정 명세에 따라 Security 설정에 명시적으로 추가해야 한다.

## 검증

```powershell
.\gradlew.bat spotlessApply build test check
```

일반 검증은 외부 서비스 없이 단위 테스트를 수행한다. Google 테스트는 테스트용
RSA 키와 모의 JWK 응답으로 서명·발급자·대상·만료를 검증한다.

`AuthIntegrationTests`는 `AUTH_INTEGRATION_TEST=true`일 때만 실행된다.
실제 PostgreSQL/Redis에 데이터를 생성하므로 별도의 비어 있는 테스트 DB와
Redis DB 번호를 준비하고, `DB_URL`과 `SPRING_DATA_REDIS_DATABASE`로 지정한다.
실사용 DB/Redis를 대상으로 실행하지 않는다. 테스트는 검증용 사용자·세션·로컬 IP
호출 제한 키를 정리한다. 실제 카카오/구글 SDK 토큰으로의 최종 연동 검증은 별도로 필요하다.
