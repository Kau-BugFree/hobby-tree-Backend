# 프론트팀 AWS 연동 안내 (2026-11-08 KST)

**공용 주소는 `https://api.hobbytreeapp.com`이다. 현재 서버는 V5 이미지이며 최신 develop의 모든 API를 지원하지 않는다.**
이번 작업은 배포 준비만 수행했다. 신규 API가 실제 배포됐다고 팀에 공지하거나 운영 RDS에 데이터를 넣지 않았다.

## 지금 확인한 것 / 업그레이드 후 확인할 것

2026-11-08 KST 읽기 전용 HTTPS 요청에서 `/actuator/health`는 `{"status":"UP"}`였다.
curl 인증서 검증을 해제하지 않았고 HTTPS 검증이 성공했다. ACM 만료일/갱신 상태의 관리 API 조회는 재실행하지 않았다.
`Origin: http://localhost:5173`, `Access-Control-Request-Headers: authorization`의 OPTIONS는 **403**이었다.
이 origin을 현재 AWS가 허용한다고 가정하면 안 된다. 다른 origin의 허용 여부도 확인한 것으로 간주하지 않는다.

| 항목 | 현재 / 전환 후 |
|---|---|
| 공용 HTTPS Health | 현재 사용 가능. UP는 DB schema/API 전체 준비의 증거가 아님 |
| 소셜 로그인/JWT 기본 경로 | V5에 존재하지만 실 provider credential 로그인은 이번에 실행하지 않음. 담당자 테스트 계정으로 재검증 필요 |
| 최신 온보딩·추천·위치·취미/나무·로드맵·단계별 활동·API-25·체험 API | V18 후보 업그레이드/배포/API smoke 이후 사용. mock 교체 시 현재 404/빈 배열을 전부 FE 버그로 판단하지 않음 |
| API-26 시작 등 미구현 범위 | V18 배포로 자동 구현되지 않음. #128 및 별도 작업 상태 확인 |
| Expo Native | 브라우저 CORS 적용 대상이 아님. HTTPS/실 provider 설정/권한/네트워크/JWT는 여전히 필요 |
| Expo Web | origin의 scheme/host/port를 정확히 등록. CORS 수정과 운영 설정 후 별도 smoke 필요 |
| 콘텐츠 화면 | Hobby/Activity 데이터가 사용자 제공 조사에서 0행. 새 schema만 올려도 화면 데이터가 생기지 않음 |

프론트 저장소 main의 package.json/app.json을 읽었다. Expo58/React Native0.88, scheme `hobbytree`,
iOS bundle/Android package `app.hobbytree`, 웹 실행 스크립트가 있다. 포트와 실제 OAuth provider console 등록은 별도 확인해야 한다.
공용 URL 변수 이름은 FE의 실제 API client 계약을 따라야 한다. `EXPO_PUBLIC_API_BASE_URL`은 사용할 수 있는 **제안 이름**이며 현재 FE에 구현돼 있다고 보고하지 않는다.

```dotenv
# 공개값만 Expo 클라이언트 번들에 둔다. 경로의 /api/v1 중복 여부를 client에서 확인한다.
EXPO_PUBLIC_API_BASE_URL=https://api.hobbytreeapp.com
```

DB/JWT/Redis 비밀·AWS 자격증명·Kakao REST 키를 `EXPO_PUBLIC_*`에 넣지 않는다.

## 인증 흐름과 진단

1. 실제 Kakao/Google provider token을 받아 `POST /api/v1/auth/social-login`에 `provider: KAKAO|GOOGLE`, `providerToken`을 보낸다.
   로그인 payload/응답 token을 console·스크린샷·공용 artifact에 남기지 않는다.
2. 서비스가 반환한 access token을 `Authorization: Bearer ...`로 전송한다. provider token/refresh token을 access token 대신 쓰지 않는다.
3. `memberStatus=PENDING_ONBOARDING`은 온보딩을 완료한 뒤 보호된 화면으로 이동한다. ACTIVE/정지/탈퇴 상태 오류를 구분한다.
4. 만료 시 `/api/v1/auth/reissue`의 현재 DTO 계약으로 갱신한다. 동시 갱신을 한 번으로 모으고 무한 재시도하지 않는다.
   새 로그인은 이전 세션을 무효화할 수 있으므로 팀원끼리 같은 계정을 공유하지 않는다.
5. 모바일은 승인한 보안 저장소를 사용한다. Web의 XSS/토큰 보관 정책은 FE 담당자가 정하고 로컬 저장소 사용을 자동 승인하지 않는다.

401은 토큰 누락/형식/서명/만료/세션 상태, 403은 회원 상태/온보딩/권한 또는 CORS를 먼저 구분한다.
브라우저 Network의 OPTIONS가403이면 본 API가 호출되지 않았을 수 있다. Origin 없이 curl로 성공했다고 Web도 된다고 판단하지 않는다.
409는 중복/현재 상태 충돌, 429는 Retry-After 확인, 5xx는 Backend/DB/Redis/외부 provider 상태를 함께 본다.
HTTP status와 공통 응답 `code/message/result`를 모두 기록하되 개인정보/인증값은 제거한다.

## CORS와 서버 설정

최신 develop에 누락된 기존 CORS hotfix를 재사용했다. 기본은 허용 origin 없음이며 wildcard는 거부한다.
명시한 origin만 `/api/v1/**`에 허용하고 Authorization/Content-Type/Idempotency-Key를 받는다.
정상 preflight는 JWT 없이 처리하지만 일반 OPTIONS를 일괄 공개하지 않는다. 최신 공개 onboarding/options 정책은 유지한다.
`application-local.yml`은 로컬5173만 허용하며 Docker build에서는 제외된다.

현재 host Compose는 CORS 환경변수를 전달하지 않는다. 이미 사용 중인 ec2 configtree에 비밀이 아닌
`cors.allowed-origins` 속성 파일을 **호스트 설정 승인 후** 제공하면 Compose/SSM v2 보호장치를 바꾸지 않고 후보에서 읽을 수 있다.
파일 이름은 정확히 `cors.allowed-origins`, 내용은 확인한 origin의 쉼표 구분 목록, UID10001/mode400이다.
앱 secret 디렉터리의 UID10001/mode500을 유지하며 변경 중에만 승인된 설치 도구로 취급한다.
`fetch-ec2-secrets.py`는 기존 네 자격 파일만 교체하므로 추가 파일 보존을 설치 점검에서 확인한다.
이 파일과 외부키는 이번 작업에서 생성/설치하지 않았다. **설정 없는 이미지만 배포하면 Web은 계속 차단된다.**

```bash
# 실제 FE origin 확인 후 읽기 전용 smoke. 8081은 예시이며 자동 허용값이 아니다.
curl -i -X OPTIONS https://api.hobbytreeapp.com/api/v1/users/me \
  -H 'Origin: http://localhost:8081' -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key'
```

| 서버 설정 | 용도 / 없는 경우 |
|---|---|
| DB_URL + 파일형 DB_USERNAME/DB_PASSWORD | RDS TLS verify-full, 제한 앱 계정. 앱 Flyway OFF/JPA validate |
| JWT_SECRET | Base64 인코딩한 최소64 random bytes. 기존 값을 보존하고 FE에 전달하지 않음 |
| KAKAO_APP_ID / GOOGLE_CLIENT_ID | provider token 검증. 숫자 Kakao 앱 ID와 Google Web client audience를 실제 provider 설정과 대조 |
| Redis host/port/파일형 password | 로그인·세션·rate limit. 기존 Redis 유지 |
| AUTH_TRUSTED_PROXY_CIDRS | 기존 검증한 ALB subnet CIDR 유지. Web origin과 다른 설정 |
| location.kakao-rest-api-key / KAKAO_REST_API_KEY | DEVICE 좌표→지역 변환. 비밀이며 현재 host 주입 경로 미확인. 비어 있으면 해당 기능은 별도 준비 대상 |
| profile-image.bucket/region / PROFILE_IMAGE_S3_BUCKET/REGION | 프로필 업로드. 버킷/region/인스턴스 역할 권한/CORS까지 별도 검증. 미설정 시 해당 경로 준비 안 됨 |
| cors.allowed-origins / CORS_ALLOWED_ORIGINS | Web 명시 origin. host는 configtree 경로 사용안, 실제 origin과 설정 승인 대기 |

카카오 REST 키나 S3 설정을 임의로 채우거나 IAM을 변경하지 않는다. configtree/Secrets Manager 주입 경로는 비밀 담당자와 확인한다.
로그인 provider의 앱 패키지/리다이렉트/서명 fingerprint, Google audience, 모바일 실기기·Web 차이를 각각 검사한다.
격리 테스트는 SocialTokenVerifier/일부 외부 연동을 모의하므로 실제 소셜 로그인과 외부 API 성공을 대신하지 않는다.

## 테스트 계정과 콘텐츠 요청 형식

운영 계정/데이터를 만들지 않았다. 담당자 승인 후 각 FE 담당자가 자기 provider 계정으로 가입하고 자신의 테스트 데이터를 만든다.
공용 계정을 만들 경우 사용자/용도/삭제 시점/개인정보 취급을 먼저 정한다. 관리자 SQL로 비밀번호/JWT/회원 상태를 임의 위조하지 않는다.
운영 seed는 **콘텐츠 담당자 자료 → BE 형식 검수 → 격리 DB fixture/API 검증 → 별도 승인 RDS 입력** 순서로 진행한다.
제공 형식은 UTF-8 JSON 또는 담당자가 관리하는 표이며 아래 관계 키로 연결한다. 예시 문구를 실제 콘텐츠로 삽입하지 않는다.

| 자료 | 최소 내용 |
|---|---|
| hobbies | 고유 hobby_code, hobby_name, 실제 설명, supported 여부·이미지 근거. 목록 비교용 최소2개 |
| hobby_stages | hobby_code, DB stage_number0–5, 실제 stage_name/설명/이미지. API 외부 stageNumber와 DB 번호 변환 계약 주의 |
| activities | 고유 activity_code, hobby_code+단계, 실제 이름·상세 설명, 시간·비용·난이도·실내외·주의·이미지 및 출처/검수자. 공개 검수 후 PUBLISHED |
| activity_steps | activity_code, 중복 없는 step_number, 원문 instruction, 승인 title/is_required, 선택 이미지. 시작 테스트용은 정비 완료·필수 단계1개 이상 |
| activity_materials | activity_code, 원문 material_name, quantity_description/null, is_required, acquisition_type/null |
| 나무/저장 상태 | 초기 계정으로 API를 통해 심기/저장. 다른 사용자 데이터는 별도 계정으로 소유권/미저장 비교 |
| experiences | 담당자 실제 체험, hobby/activity 연결, 실제 지역/제공자/공개상태, 미래 회차·duration·capacity·준비물/취소정책. 예약/외부 결제 동작은 임의 생성 금지 |

최소 화면 조합: 빈 계정1, 온보딩 완료 계정1, 취미2, 취미별 공개 단계·활동, 활동별 실제 실행 단계·준비물,
저장/미저장 상태와 API-25 nullable 예시, 회차 있는/없는 체험 및 empty 목록이다.
성장 진행 화면까지 테스트하려면 현재 단계의 **서로 다른 활동3개** 등 도메인 성장 규칙에 맞는 추가 콘텐츠가 필요하다.
확정하지 않은 title/isRequired/contentUpdatedAt·description·quantity·acquisitionType은 추론하거나0/false/현재날짜로 채우지 않는다.
조회 허용과 시작 가능은 다르다. API-26 미정비/필수0 차단은 #128의 별도 구현 범위다.

## 오류 전달용 정보

발생 KST 시각, 앱 native/Web·실기기/브라우저, base URL, 경로/method, token 제거한 status/응답 code,
Origin(웹), 재현 단계, 배포 revision/digest를 전달한다. 실 사용자 식별자나 provider token은 넣지 않는다.
Backend 담당자는 Health → Docker exact image/health → ALB → DB history/schema → Redis → 외부 provider 순으로 좁힌다.
로그는 승인된 제한 접근으로 확인하고 raw `docker inspect`, 환경, Authorization, DB secret을 공유하지 않는다.
배포 중 API 중단은 공지한 점검 창과 대조한다. 신규 데이터가 없어서 목록이 빈 것과 SQL/route 오류를 구분한다.
