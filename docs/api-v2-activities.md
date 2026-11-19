# 활동 APIv2 확정 계약 — API-23~33·50

팀 APIv2.md의 활동 파트만 발췌해 동기화한 명세다. 다른 도메인과 API 번호는 변경하지 않는다.
기준 develop: `dc375a4bf0b6ba535b29d504a5f87ae20113a2bb`, 확인일 2026-11-19(Asia/Seoul). 구현·병합 상태와 운영 반영 상태는 별개다.

## 적용 범위와 알려진 후속

- API-23~33·50은 develop에 구현·병합됐다. 이 문서는 원본의 확정 정책 누락을 보완하며 실제 계정/앱 E2E나 운영 적용 결과를 뜻하지 않는다.
- API-24 v2 노출 순서/커서, API-23 서비스 상태 검사, API-31 공개 판정과 과거 기록·재전송·삭제 정책을 반영한다.
- API-31~33의 ANONYMIZED 직접 서비스 호출 검증은 [#249](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/249) 후속이다. 현재 HTTP 경로는 공통 필터가 차단한다.
- 실제 콘텐츠 [#126](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/126), FE 확인 [#127](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/127), 공개 정합성 [#129](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/129), 편집 운영 [#130](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/130), 이미지 보존 [#242](https://github.com/Kau-BugFree/hobby-tree-Backend/issues/242)는 완료로 간주하지 않는다.
- COMMON429_1은 목표 계약이며 활동 API의 공통 Rate Limit은 미구현이다. 제휴·코치/추천 이력 직접 조회 등 별도 기능을 이 명세 동기화로 구현했다고 표시하지 않는다.
- 과거 기록 처리 및 현재/이력 문서 구분은 [통합 현황](activity-contract-status.md)을 함께 참고한다.

## API-23 취미별 단계 목록

### 로직 설명

1. isSupported=true인 취미인지 확인하고 등록된 단계 정의 전체를 단계 번호 순으로 조회한다. 현재 단계별 활성화·공개 플래그는 없다.
2. 단계별 목표·소개·활동 개수와 필요한 누적 완료 수를 반환한다.
3. 개인별 잠금 상태가 필요한 화면은 API 22의 나무 로드맵을 사용한다.

### API Endpoint

`GET /api/v1/hobbies/{hobbyId}/stages`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| hobbyId | integer | Y | 취미 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "ACTIVITY200_1",
  "message": "취미 단계 목록을 조회했습니다.",
  "result": {
    "hobbyId": 10,
    "hobbyName": "드로잉",
    "stages": [
      {
        "stageId": 101,
        "stageNumber": 1,
        "name": "새싹",
        "description": "선과 기본 형태 익히기",
        "activityCount": 3,
        "unlockAtDistinctCompletedCount": 0
      },
      {
        "stageId": 102,
        "stageNumber": 2,
        "name": "묘목",
        "description": "구도와 표현 확장하기",
        "activityCount": 3,
        "unlockAtDistinctCompletedCount": 3
      },
      {
        "stageId": 103,
        "stageNumber": 3,
        "name": "나무",
        "description": "나만의 작품 만들기",
        "activityCount": 3,
        "unlockAtDistinctCompletedCount": 6
      }
    ]
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**HOBBY404_1**

HTTP 404 · 취미가 없거나 비공개 상태임

```json
{
  "isSuccess": false,
  "code": "HOBBY404_1",
  "message": "취미가 없거나 비공개 상태임",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`hobbies` · `hobby_stages` · `activities`

**비고·정책**

- 개인 나무 생성 전에도 탐색할 수 있는 카탈로그 정보다. 열람과 실제 활동 시작 권한은 구분한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 서비스에서도 ACTIVE만 허용한다. PENDING_ONBOARDING/SUSPENDED/WITHDRAWN은 기존 AUTH403_2/1/3, ANONYMIZED 등 나머지 상태와 null/없는 회원은 AUTH401_2로 거절한다.
- 공개 활동 수는 PUBLISHED만 집계한다. 설명 미상은null, 미등록 단계는 만들지 않고 필요한 누적 완료 수는 내부 stage_number×당해 취미의 activities_per_stage로 구한다.

## API-24 단계별 활동 목록

### 로직 설명

1. 등록된 단계와 소속 취미의 isSupported=true 여부를 확인하고 PUBLISHED 활동만 조회한다.
2. 해당 단계의 활동을 display_order ASC NULLS LAST, activity_id ASC로 조회한다.
3. 활동 요약과 본인의 저장 여부를 반환한다. 잠긴 단계의 활동도 미리 볼 수 있다.

### API Endpoint

`GET /api/v1/hobby-stages/{stageId}/activities`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| stageId | integer | Y | 취미 단계 ID; 양의 정수 |

### Query String

| Key | 타입 | 필수 | 기본값·제약 | 설명 |
| --- | --- | --- | --- | --- |
| cursor | string | N | 서버 발급 api24-order-v2 커서 | 노출 순서·미지정(null)·ID 기준 다음 페이지 |
| size | integer | N | 기본 20; 1~50 | 페이지 크기 |

요청 URL 예시:

```http
GET /api/v1/hobby-stages/101/activities?size=20
```

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "ACTIVITY200_2",
  "message": "단계별 활동 목록을 조회했습니다.",
  "result": {
    "stageId": 101,
    "hobbyId": 10,
    "stageNumber": 1,
    "items": [
      {
        "activityId": 1003,
        "title": "명암 넣기",
        "thumbnailUrl": "https://cdn.example.com/hobbies/drawing.png",
        "durationMinutes": 30,
        "estimatedCost": 0,
        "difficulty": "BEGINNER",
        "indoorOutdoor": "INDOOR",
        "isSaved": false
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**STAGE404_1**

HTTP 404 · 단계가 없거나 열람할 수 없음

```json
{
  "isSuccess": false,
  "code": "STAGE404_1",
  "message": "단계가 없거나 열람할 수 없음",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`hobby_stages` · `hobbies` · `activities` · `saved_activities`

**비고·정책**

- 열람 가능하더라도 현재 나무의 성장 단계보다 높은 활동은 API 26에서 시작을 제한한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- display_order는 단계별 nullable INTEGER(0~2147483647)다. 동순위를 허용하며 ID로 구분한다. 미지정은 null로 보존하고 지정 순위 뒤에서 ID순으로 조회하며 임의 순번을 백필하지 않는다.
- 커서는 사용자·단계·순위/null·ID에 묶인 서명값이다. 구 api24-id-v1은 COMMON400_1이며 FE는 커서를 지우고 첫 페이지를 재조회한다. 내용을 해석·변환하거나 같은 구 커서를 무한 재시도하지 않는다.
- 기준 행을 다시 조회하지 않으므로 삭제·이동 후에도 당시 정렬 키로 이어간다. 요청 하나는 일관된 읽기 스냅샷이나 여러 페이지를 고정하지는 않으며 순위 편집 뒤 최신 전체 순서는 첫 페이지부터 조회한다.
- 시간·비용·난이도·이미지는 미상일 때null이며 0/기본 난이도로 만들지 않는다. 썸네일은 활동→취미→null이다. 순위만 편집하면 실행 콘텐츠 버전/시작 스냅샷은 바뀌지 않는다.

## API-25 활동 상세 및 준비물·단계 조회

### 로직 설명

1. 활동이 PUBLISHED이고 등록된 상위 단계의 취미가 isSupported=true인지 확인한다. 단계별 별도 공개 플래그는 없다.
2. 활동 소개·예상 비용과 시간·준비물·실행 단계를 조회한다.
3. 준비물의 필수 여부와 획득 방식, 콘텐츠 갱신 시각을 포함해 반환한다.

### API Endpoint

`GET /api/v1/activities/{activityId}`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| activityId | integer | Y | 활동 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "ACTIVITY200_3",
  "message": "활동 상세를 조회했습니다.",
  "result": {
    "activityId": 1003,
    "title": "명암 넣기",
    "thumbnailUrl": "https://cdn.example.com/hobbies/drawing.png",
    "durationMinutes": 30,
    "estimatedCost": 0,
    "difficulty": "BEGINNER",
    "indoorOutdoor": "INDOOR",
    "isSaved": false,
    "hobbyId": 10,
    "stageId": 101,
    "stageNumber": 1,
    "description": "간단한 사물을 두 가지 명암으로 표현해 봅니다.",
    "contentVersion": 1,
    "materials": [
      {
        "materialId": 501,
        "name": "연필",
        "quantity": "1자루",
        "isRequired": true,
        "acquisitionType": "OWN_OR_BUY"
      },
      {
        "materialId": 502,
        "name": "종이",
        "quantity": "1장",
        "isRequired": true,
        "acquisitionType": "OWN_OR_BUY"
      }
    ],
    "steps": [
      {
        "stepId": 10031,
        "stepNumber": 1,
        "title": "빛 방향 정하기",
        "description": "사물에 빛이 오는 방향을 정해 보세요.",
        "isRequired": true,
        "imageUrl": null
      },
      {
        "stepId": 10032,
        "stepNumber": 2,
        "title": "밝고 어두운 면 표현하기",
        "description": "연필 힘을 조절하며 두 가지 명암으로 표현해 보세요.",
        "isRequired": true,
        "imageUrl": null
      }
    ],
    "cautions": [
      "밝은 곳에서 편안한 자세로 진행하세요."
    ],
    "contentUpdatedAt": "2026-10-20T12:00:00+09:00"
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**ACTIVITY404_1**

HTTP 404 · 활동이 없거나 비공개 상태임

```json
{
  "isSuccess": false,
  "code": "ACTIVITY404_1",
  "message": "활동이 없거나 비공개 상태임",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activities` · `hobbies` · `hobby_stages` · `activity_steps` · `activity_materials` · `saved_activities`

**비고·정책**

- acquisitionType 제안값: OWN(보유품), BUY(구매), RENT(대여), OWN_OR_BUY(보유 또는 구매), NONE(준비물 없음).
- 제휴 콘텐츠는 API 49로 별도 조회한다. 이 API의 steps는 실행 절차이며 사용자 성장 단계인 hobby_stages와 구분한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 사용자 승인 MVP: steps[].title/isRequired/imageUrl, contentUpdatedAt, description, thumbnailUrl, durationMinutes, estimatedCost, difficulty, materials[].quantity/acquisitionType은 미상 시null을 허용한다. null은 false/0/NONE이 아니다.
- contentVersion은 도입 baseline1이며 실제 콘텐츠 변경 시 증가한다. 과거 contentUpdatedAt 불명은null로 보존한다. description은 상세 원문이고 cautions는 원문 전체1항목 또는 빈 배열이다.
- 기존 acquisitionType 문자열 또는null을 반환하며 위 제안 목록을 강제 enum으로 적용하거나 NONE으로 치환하지 않는다. 준비물/실행 단계 미등록은 조회에서 빈 배열일 수 있다.
- 조회 성공은 시작 허용을 뜻하지 않는다. 미정비 제목/필수 여부·실행 단계 미등록·필수 단계0 등은 API-26 신규 시작에서 ACTIVITY404_1로 거절한다.
- nullable의 실제 FE 확인은 #127, 실제 콘텐츠 정비는 #126, 이미지 파일 보존은 #242, 편집/복원 규약은 #130 후속이다. 담당자 승인이 끝났다고 간주하지 않는다.

## API-26 활동 시작

### 로직 설명

1. 나무 소유권과 ACTIVE 상태를 확인한다.
2. activityId가 해당 나무의 취미에 속하며 현재 단계 이하에서 시작할 수 있는 활동인지 확인한다.
3. 같은 나무의 진행 기록에 시작 스냅샷이 있는지 먼저 확인한다. 같은 활동이면 기존 기록을 반환하고 다른 활동이면 먼저 마무리하도록 충돌을 반환한다.
4. 새 기록을 IN_PROGRESS로 생성하고 활동 내용·실행 단계 버전을 스냅샷으로 고정한다.
5. 이미 완료한 활동도 재시작할 수 있다. 시작 자체는 성장에 반영하지 않고, 완료 시 같은 활동의 현재 유효 기여가 있으면 중복 부여하지 않는다.

### API Endpoint

`POST /api/v1/user-hobbies/{userHobbyId}/activity-records`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| userHobbyId | integer | Y | 본인 소유 취미 나무 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Content-Type | application/json | Y | JSON 요청 본문 |
| Accept | application/json | N | 응답 형식 |

### Request-body

```json
{
  "activityId": 1003
}
```

| Key | 타입 | 필수 | 제약 | 설명 |
| --- | --- | --- | --- | --- |
| activityId | integer | Y | 나무와 같은 취미의 공개 활동 | 시작할 활동 ID |

### Response-body · HTTP 201

```json
{
  "isSuccess": true,
  "code": "RECORD201_1",
  "message": "활동을 시작했습니다.",
  "result": {
    "recordId": 401,
    "userHobbyId": 201,
    "activityId": 1003,
    "status": "IN_PROGRESS",
    "contentVersion": 1,
    "completedStepIds": [],
    "progressPercent": 0,
    "version": 1,
    "startedAt": "2026-10-29T15:00:00+09:00",
    "reused": false
  }
}
```

### Response-body — 동일 활동 이어하기 · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_1",
  "message": "진행 중인 활동을 조회했습니다.",
  "result": {
    "recordId": 401,
    "userHobbyId": 201,
    "activityId": 1003,
    "status": "IN_PROGRESS",
    "contentVersion": 1,
    "completedStepIds": [
      10031
    ],
    "progressPercent": 50,
    "version": 2,
    "startedAt": "2026-10-29T15:00:00+09:00",
    "reused": true
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**TREE404_1**

HTTP 404 · 본인 소유 나무를 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "TREE404_1",
  "message": "본인 소유 나무를 찾을 수 없음",
  "result": null
}
```

**ACTIVITY404_1**

HTTP 404 · 활동이 없거나 시작할 수 없음

```json
{
  "isSuccess": false,
  "code": "ACTIVITY404_1",
  "message": "활동이 없거나 시작할 수 없음",
  "result": null
}
```

**RECORD400_1**

HTTP 400 · 활동이 나무의 취미에 속하지 않음

```json
{
  "isSuccess": false,
  "code": "RECORD400_1",
  "message": "활동이 나무의 취미에 속하지 않음",
  "result": null
}
```

**ACTIVITY403_1**

HTTP 403 · 아직 열리지 않은 성장 단계의 활동

```json
{
  "isSuccess": false,
  "code": "ACTIVITY403_1",
  "message": "아직 열리지 않은 성장 단계의 활동",
  "result": null
}
```

**TREE409_4**

HTTP 409 · 나무가 ACTIVE 상태가 아님

```json
{
  "isSuccess": false,
  "code": "TREE409_4",
  "message": "나무가 ACTIVE 상태가 아님",
  "result": null
}
```

**RECORD409_1**

HTTP 409 · 같은 나무에서 다른 활동을 진행 중

```json
{
  "isSuccess": false,
  "code": "RECORD409_1",
  "message": "같은 나무에서 다른 활동을 진행 중",
  "result": null
}
```

**RECORD409_5**

HTTP 409 · 과거 진행 기록의 시작 스냅샷이 없어 시작·이어하기를 처리할 수 없음; 기록 보존·정비 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_5",
  "message": "과거 진행 기록의 시작 스냅샷 정비가 필요합니다.",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`user_hobbies` · `hobbies` · `activities` · `activity_steps` · `activity_records` · `activity_record_contents` · `activity_record_steps`

**비고·정책**

- 한 나무의 IN_PROGRESS는 DB 고유 제약으로 최대 1개를 보장한다. 생성과 중복 확인을 하나의 트랜잭션으로 처리한다.
- 콘텐츠 스냅샷을 보관해 운영 중 원본 단계가 바뀌어도 진행 기록의 stepId와 완료 조건을 유지한다.
- 현재 목록에는 활동 취소 API가 없으므로 중단은 API 29의 PARTIAL 또는 NOT_DONE 종료로 처리한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 회원과 나무는 ACTIVE여야 한다. 신규 시작은 같은 취미의 PUBLISHED·지원 콘텐츠이며 현재 나무 단계 이하, 실행 단계 제목/필수 여부 등 정비 완료와 필수 단계1개 이상을 요구한다.
- 시작 스냅샷이 없는 과거 IN_PROGRESS가 남으면 다른 활동 시작도 RECORD409_5로 거절한다. 기록·시각·진행을 보존하고 현재 콘텐츠로 과거 스냅샷을 만들지 않는다.
- 유효한 기존 스냅샷의 동일 활동 이어하기는 원본 카탈로그가 수정/숨김돼도 기록 ID·시각·버전·체크를 유지한다. 회원/나무 ACTIVE 검사는 계속 적용한다.
- 성장 기여가 API-50으로 취소된 활동도 새 기록으로 다시 시작할 수 있다. 성장은 API-29에서 당시 유효 기여가 없을 때만 새로 반영한다.
- 과거 스냅샷 없는 진행은 API-28/29도409_5이며 API-50은 진행 중409_6이다. 자동 종료/복원 우회는 없고 별도 정비 정책·승인이 필요하다.

## API-27 활동 진행 상황 조회

### 로직 설명

1. 활동 기록이 본인 소유 나무에 속하는지 확인한다.
2. 시작 당시 콘텐츠 스냅샷과 완료한 실행 단계·진행률·수정 버전을 조회한다.
3. 나무 상태와 기록 상태에 따라 canUpdate를 계산하고, 종료된 기록이면 피드백과 종료 시각을 함께 반환한다.

### API Endpoint

`GET /api/v1/activity-records/{recordId}`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| recordId | integer | Y | 본인 소유 활동 기록 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_2",
  "message": "활동 진행 상황을 조회했습니다.",
  "result": {
    "recordId": 401,
    "userHobbyId": 201,
    "activityId": 1003,
    "title": "명암 넣기",
    "status": "IN_PROGRESS",
    "treeStatus": "ACTIVE",
    "contentVersion": 1,
    "steps": [
      {
        "stepId": 10031,
        "stepNumber": 1,
        "title": "빛 방향 정하기",
        "description": "사물에 빛이 오는 방향을 정해 보세요.",
        "isRequired": true,
        "imageUrl": null
      },
      {
        "stepId": 10032,
        "stepNumber": 2,
        "title": "밝고 어두운 면 표현하기",
        "description": "연필 힘을 조절하며 두 가지 명암으로 표현해 보세요.",
        "isRequired": true,
        "imageUrl": null
      }
    ],
    "completedStepIds": [
      10031
    ],
    "progressPercent": 50,
    "elapsedSeconds": 600,
    "version": 2,
    "canUpdate": true,
    "startedAt": "2026-10-29T15:00:00+09:00",
    "finishedAt": null,
    "feedback": null
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**RECORD404_1**

HTTP 404 · 본인 소유의 활동 기록을 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "RECORD404_1",
  "message": "본인 소유의 활동 기록을 찾을 수 없음",
  "result": null
}
```

**RECORD409_5**

HTTP 409 · 과거/체험 인정 기록의 시작 스냅샷이 없어 상세를 복원할 수 없음; 기록 보존·정비 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_5",
  "message": "과거 진행 기록의 시작 스냅샷 정비가 필요합니다.",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activity_records` · `activity_record_contents` · `activity_record_steps` · `user_hobbies`

**비고·정책**

- 타인의 recordId도 404로 처리한다. PAUSED·ENDED 나무의 기록은 열람 가능하지만 canUpdate:false다.
- 원본 활동이 수정되거나 숨겨졌더라도 본인의 기록 스냅샷은 조회할 수 있다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 시작 스냅샷 없는 과거/체험 인정 기록은 종료 상태라도 RECORD409_5다. 가짜 제목·단계·진행률·버전으로 상세를 복원하지 않는다.
- canUpdate는 IN_PROGRESS이고 나무가ACTIVE일 때만true다. 종료 상태·PAUSED/ENDED 나무도 스냅샷이 있으면 열람한다.
- 삭제된 본인 기록도 RECORD404_1이며 삭제 여부를 나무 상태·스냅샷 판정에 앞서 확인한다. 종료 피드백은 저장된 값을 반환하고 없으면null이다.

## API-28 활동 단계 진행 저장

### 로직 설명

1. 기록 소유권·IN_PROGRESS·나무 ACTIVE 상태를 확인한다.
2. expectedVersion이 현재 기록 버전과 일치하는지 확인한다.
3. completedStepIds 전체를 현재 체크 완료 목록으로 교체한다. 모든 ID가 기록 스냅샷에 속하고 중복이 없는지 검증한다.
4. 진행률을 서버에서 계산하고 경과 시간과 진행 정보를 저장한 뒤 version을 증가시킨다.

### API Endpoint

`PATCH /api/v1/activity-records/{recordId}/progress`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| recordId | integer | Y | 본인 소유 활동 기록 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Content-Type | application/json | Y | JSON 요청 본문 |
| Accept | application/json | N | 응답 형식 |

### Request-body

```json
{
  "completedStepIds": [
    10031,
    10032
  ],
  "elapsedSeconds": 1200,
  "expectedVersion": 1
}
```

| Key | 타입 | 필수 | 제약 | 설명 |
| --- | --- | --- | --- | --- |
| completedStepIds | integer[] | Y | 기록에 속한 실행 단계 ID; 중복 불가 | 완료 목록 전체; []로 체크 해제 가능 |
| elapsedSeconds | integer | N | 0~86400; 생략/null이면 기존 값 유지 | 제공하면 감소를 포함한 보고값 그대로 저장; 성장 판단에 사용하지 않음 |
| expectedVersion | integer | Y | 현재 기록 version | 낙관적 잠금 버전 |

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_3",
  "message": "활동 진행 상황이 저장되었습니다.",
  "result": {
    "recordId": 401,
    "status": "IN_PROGRESS",
    "completedStepIds": [
      10031,
      10032
    ],
    "progressPercent": 100,
    "elapsedSeconds": 1200,
    "version": 2,
    "updatedAt": "2026-10-29T15:20:00+09:00"
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**RECORD404_1**

HTTP 404 · 본인 소유의 활동 기록을 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "RECORD404_1",
  "message": "본인 소유의 활동 기록을 찾을 수 없음",
  "result": null
}
```

**RECORD400_2**

HTTP 400 · 다른 활동의 단계 ID·중복 ID·잘못된 시간

```json
{
  "isSuccess": false,
  "code": "RECORD400_2",
  "message": "다른 활동의 단계 ID·중복 ID·잘못된 시간",
  "result": null
}
```

**RECORD409_2**

HTTP 409 · 이미 종료된 기록

```json
{
  "isSuccess": false,
  "code": "RECORD409_2",
  "message": "이미 종료된 기록",
  "result": null
}
```

**RECORD409_3**

HTTP 409 · 수정 버전 불일치

```json
{
  "isSuccess": false,
  "code": "RECORD409_3",
  "message": "수정 버전 불일치",
  "result": null
}
```

**TREE409_4**

HTTP 409 · 나무가 ACTIVE 상태가 아님

```json
{
  "isSuccess": false,
  "code": "TREE409_4",
  "message": "나무가 ACTIVE 상태가 아님",
  "result": null
}
```

**RECORD409_5**

HTTP 409 · 과거 진행 기록의 시작 스냅샷이 없어 진행 저장을 처리할 수 없음; 기록 보존·정비 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_5",
  "message": "과거 진행 기록의 시작 스냅샷 정비가 필요합니다.",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activity_records` · `activity_record_contents` · `activity_record_steps` · `user_hobbies`

**비고·정책**

- 필수 단계 완료 수 / 전체 필수 단계 수로 진행률을 계산하고 정수 내림한다. 필수 단계가 없거나 미정비인 콘텐츠는 API-26 신규 시작을 거절하며 원본 공개 상태를 자동 변경하지 않는다.
- 100% 저장만으로 활동이 완료되지는 않는다. 피드백과 성장 반영은 API 29에서 처리한다.
- 이미 체크한 항목을 되돌릴 수 있으며 실행 단계의 순서를 강제하지 않는다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 시작 스냅샷 없는 과거 진행은 RECORD409_5다. 이미 종료된 비삭제 기록은 RECORD409_2이며 나무 ACTIVE 등 선행 조건은 그대로 적용한다.
- elapsedSeconds 생략/null은 기존 값 유지, 제공하면 감소도 포함해 0~86400 보고값을 그대로 저장한다. max() 보정·서버 시간 추정·성장 판단에 사용하지 않는다.
- 현재 expectedVersion으로 저장하면 체크 목록이 같아도 version을1 증가시킨다. stale 재요청은 RECORD409_3이며 중복/스냅샷 밖 단계 ID는 RECORD400_2다.
- completedStepIds 전체를 교체하고 선택 단계는 완료 목록에만 넣는다. 선택 단계는 진행률 분모/분자에 포함하지 않으며 삭제 기록은 RECORD404_1이다.

## API-29 활동 완료 및 피드백

### 로직 설명

1. 기록 소유권과 나무 ACTIVE 상태를 확인한다. 이미 종료된 경우 동일한 종료 내용이면 기존 결과를 반환하고, 내용이 다르면 409로 거절한다.
2. 첫 종료 요청은 IN_PROGRESS와 expectedVersion 일치를 확인한다. COMPLETED면 스냅샷의 모든 필수 실행 단계가 체크되었는지 확인한다.
3. COMPLETED·PARTIAL·NOT_DONE 중 요청한 상태로 종료하고 서버 finishedAt·피드백을 저장한다.
4. COMPLETED일 때만 (user_hobby_id, activity_id) 성장 기여를 UNIQUE로 추가한다. 같은 활동 재완료 및 외부 참여에서 이미 인정된 활동은 중복 기여하지 않는다.
5. 누적 고유 완료 활동 수를 기준으로 단계와 진행 수치를 갱신한다. 기록 종료·기여 추가·단계 변경을 하나의 트랜잭션으로 처리한다.

### API Endpoint

`PATCH /api/v1/activity-records/{recordId}/complete`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| recordId | integer | Y | 본인 소유 활동 기록 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Content-Type | application/json | Y | JSON 요청 본문 |
| Accept | application/json | N | 응답 형식 |

### Request-body

```json
{
  "status": "COMPLETED",
  "expectedVersion": 2,
  "feedback": {
    "satisfactionScore": 4,
    "perceivedDifficulty": "APPROPRIATE",
    "memo": "짧게 하기 좋아요."
  }
}
```

| Key | 타입 | 필수 | 제약 | 설명 |
| --- | --- | --- | --- | --- |
| status | string | Y | COMPLETED, PARTIAL, NOT_DONE | 종료 결과 |
| expectedVersion | integer | Y | 현재 기록 version | 첫 종료 시 동시 수정 검증 |
| feedback | object | 조건부 | COMPLETED·PARTIAL일 때 필수 | 만족도 및 체감 난이도 |
| feedback.satisfactionScore | integer | 조건부 | feedback를 보내면 필수; 1~5 | 만족도 |
| feedback.perceivedDifficulty | string | 조건부 | feedback를 보내면 필수; EASY, APPROPRIATE, HARD | 체감 난이도 |
| feedback.memo | string | N | 최대 1000자 | 개인 활동 메모 |
| notDoneReason | string | 조건부 | NOT_DONE이면 필수; 1~200자 | 활동하지 못한 이유 |

**Request Body — 일부만 수행하고 종료**

```json
{
  "status": "PARTIAL",
  "expectedVersion": 2,
  "feedback": {
    "satisfactionScore": 3,
    "perceivedDifficulty": "HARD",
    "memo": "다음에 다시 해볼게요."
  }
}
```

**Request Body — 시도하지 않고 종료**

```json
{
  "status": "NOT_DONE",
  "expectedVersion": 1,
  "notDoneReason": "오늘 시간이 부족해요."
}
```

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_4",
  "message": "활동이 완료 처리되었습니다.",
  "result": {
    "recordId": 401,
    "userHobbyId": 201,
    "activityId": 1003,
    "status": "COMPLETED",
    "progressPercent": 100,
    "version": 3,
    "finishedAt": "2026-10-29T15:20:00+09:00",
    "feedback": {
      "satisfactionScore": 4,
      "perceivedDifficulty": "APPROPRIATE",
      "memo": "짧게 하기 좋아요."
    },
    "growth": {
      "growthApplied": true,
      "distinctCompletedActivityCount": 3,
      "previousStageNumber": 1,
      "currentStageNumber": 2,
      "stageChanged": true,
      "isMaxStage": false
    }
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**RECORD404_1**

HTTP 404 · 본인 소유의 활동 기록을 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "RECORD404_1",
  "message": "본인 소유의 활동 기록을 찾을 수 없음",
  "result": null
}
```

**RECORD400_3**

HTTP 400 · 종료 상태·필수 피드백·미수행 이유가 올바르지 않음

```json
{
  "isSuccess": false,
  "code": "RECORD400_3",
  "message": "종료 상태·필수 피드백·미수행 이유가 올바르지 않음",
  "result": null
}
```

**RECORD409_2**

HTTP 409 · 이미 종료된 기록에 다른 종료 내용으로 요청했거나 보관된 최초 API-29 응답이 없음

```json
{
  "isSuccess": false,
  "code": "RECORD409_2",
  "message": "이미 종료된 기록에 다른 종료 내용으로 요청함",
  "result": null
}
```

**RECORD409_3**

HTTP 409 · 수정 버전 불일치

```json
{
  "isSuccess": false,
  "code": "RECORD409_3",
  "message": "수정 버전 불일치",
  "result": null
}
```

**RECORD409_4**

HTTP 409 · COMPLETED를 요청했지만 필수 실행 단계가 남아 있음

```json
{
  "isSuccess": false,
  "code": "RECORD409_4",
  "message": "COMPLETED를 요청했지만 필수 실행 단계가 남아 있음",
  "result": null
}
```

**TREE409_4**

HTTP 409 · 나무가 ACTIVE 상태가 아님

```json
{
  "isSuccess": false,
  "code": "TREE409_4",
  "message": "나무가 ACTIVE 상태가 아님",
  "result": null
}
```

**RECORD409_5**

HTTP 409 · 과거 진행 기록의 시작 스냅샷이 없어 최초 종료를 처리할 수 없음; 기록 보존·정비 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_5",
  "message": "과거 진행 기록의 시작 스냅샷 정비가 필요합니다.",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activity_records` · `activity_record_contents` · `activity_record_steps` · `activity_completion_receipts` · `activity_growth_contributions` · `user_hobbies` · `hobbies`

**비고·정책**

- PARTIAL·NOT_DONE도 이 엔드포인트에서 종료 처리한다. 성장에는 반영하지 않는다.
- 현재 유효 기여 수를 N, 취미의 activities_per_stage를 k라 하면 외부 단계는 min(6, 1 + floor(N/k))다. 내부 단계 0~5를 외부 1~6으로 변환하며 3을 하드코딩하지 않는다. 만족도·체감 난이도는 피드백으로 저장하고 성장 조건·벌점에 쓰지 않는다. 추천/코치의 피드백 활용 완료를 뜻하지 않는다.
- growthApplied:true는 이 기록이 성장 기여를 처음 만들었다는 의미다. 동일 종료 요청을 재전송해 같은 응답을 받아도 기여를 추가하지 않는다.
- 동일 종료 여부는 status·feedback·notDoneReason 등 종료 내용으로 판단하며 expectedVersion은 비교 내용에서 제외한다. 최초 성공 응답의 성장 결과를 보관해 재전송한다.
- 종료 후 피드백 수정 API는 원본 목록에 없으므로 이 명세에는 추가하지 않는다. 필요 여부는 확정 사항에 기록한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 시작 스냅샷 없는 과거 IN_PROGRESS는 RECORD409_5, 보관된 최초 API-29 응답이 없는 과거/체험 종료는 RECORD409_2다. 현재 데이터로 성공 응답을 만들어 내지 않는다.
- 동일 종료 비교에서 expectedVersion은 제외하되 요청 필수값/타입/양수 검증은 유지한다. 선택 필드의 생략과null은 같고 메모·이유의 문자열은 공백까지 그대로 비교한다.
- 상태와 관계없이 제공한 유효 feedback/notDoneReason을 저장·비교한다. COMPLETED/PARTIAL은feedback, NOT_DONE은 공백만 아닌 이유가 필수다. memo≤1000·이유1~200 Unicode code point이며 NUL/깨진 surrogate는 RECORD400_3이다.
- 동일 재요청은 최초 finishedAt/version/feedback/growth 결과 그대로다. 이후 성장·원본 참조 이전을 반영해 과거 응답을 다시 만들지 않는다. 재전송도 ACTIVE 회원·소유권·ACTIVE 나무를 요구한다.
- 현재 유효 성장 기여는 나무·활동 쌍당1개다. 반복/체험 완료의 중복 기여는 없고 API-50으로 마지막 기여가 취소된 뒤의 새 완료는 다시 기여할 수 있다.
- 원장·mirror·성장 기준선 불일치 시 자동 보정하지 않고 전체 rollback/COMMON500_1로 정비를 요구한다. 삭제 기록은 최초 완료 결과 재전송보다 먼저 RECORD404_1로 거절한다.
- 피드백·미수행 이유는 activity_records 컬럼에, 최초 요청/응답은 activity_completion_receipts에 저장한다. 응답에 없는 notDoneReason 등 필드를 임의 추가하지 않는다.

## API-30 취미 나무별 활동 기록 조회

### 로직 설명

1. 나무가 본인 소유인지 확인한다.
2. 삭제 기록을 제외한 뒤 선택한 상태와 커서를 적용해 startedAt DESC NULLS LAST, recordId DESC로 조회한다.
3. 활동 제목·진행률·피드백 요약·성장 반영 여부를 함께 반환한다. 반복 시도는 각각의 recordId로 표시한다.

### API Endpoint

`GET /api/v1/user-hobbies/{userHobbyId}/activity-records`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| userHobbyId | integer | Y | 본인 소유 취미 나무 ID; 양의 정수 |

### Query String

| Key | 타입 | 필수 | 기본값·제약 | 설명 |
| --- | --- | --- | --- | --- |
| status | string | N | IN_PROGRESS, COMPLETED, PARTIAL, NOT_DONE | 생략 시 전체 |
| cursor | string | N | 서버 발급 커서 | 다음 페이지 |
| size | integer | N | 기본 20; 1~50 | 페이지 크기 |

요청 URL 예시:

```http
GET /api/v1/user-hobbies/201/activity-records?status=COMPLETED&size=20
```

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_5",
  "message": "취미 나무별 활동 기록을 조회했습니다.",
  "result": {
    "userHobbyId": 201,
    "items": [
      {
        "recordId": 401,
        "activityId": 1003,
        "title": "명암 넣기",
        "status": "COMPLETED",
        "progressPercent": 100,
        "startedAt": "2026-10-29T15:00:00+09:00",
        "finishedAt": "2026-10-29T15:20:00+09:00",
        "satisfactionScore": 4,
        "perceivedDifficulty": "APPROPRIATE",
        "growthApplied": true
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**TREE404_1**

HTTP 404 · 본인 소유의 취미 나무를 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "TREE404_1",
  "message": "본인 소유의 취미 나무를 찾을 수 없음",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`user_hobbies` · `activity_records` · `activity_record_contents` · `activity_record_steps` · `activities` · `activity_growth_contributions`

**비고·정책**

- 성장 기여의 원천이 외부 참여 확인인 경우에도 연결된 활동 기록으로 함께 조회한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- PAUSED/ENDED를 포함한 본인 나무를 조회한다. 신규 스냅샷 형식은 시작 당시 제목/필수 체크 진행률을 사용하며 원본 공개/지원 여부로 기록을 숨기지 않는다.
- 과거 형식/체험 인정 기록도 포함하고 현재 활동 제목을 사용한다. 알 수 없는 progressPercent와 원래 null startedAt/finishedAt/피드백은null로 보존한다. null 시작 시각은 맨 뒤에서 ID 내림차순이다.
- 신규 스냅샷 형식 자체가 손상된 경우를 과거 형식의null로 감추지 않고 COMMON500_1로 정비를 요구한다.
- growthApplied는 현재 유효 원장의 origin_record_id가 이 recordId인지 뜻한다. 같은 활동의 기여가 있다는 이유로 반복 기록을 모두true로 만들지 않으며 API-50 원본 이전 뒤 현재 값은 바뀔 수 있다.
- 커서는 사용자·나무·상태 필터에 묶인다. 삭제 행을 LIMIT/커서 계산 전에 제외하며 삭제된 기준 행도 원래 정렬 키로 이어간다. 여러 페이지 전체의 시점 고정은 보장하지 않는다.

## API-31 활동 저장

### 로직 설명

1. 활동이 PUBLISHED이고 현재 소속 단계의 취미가 isSupported=true인지 확인한다.
2. (user_id, activity_id) 저장 관계가 없으면 생성한다.
3. 이미 저장한 활동이면 기존 savedAt을 유지하고 성공으로 응답한다.

### API Endpoint

`PUT /api/v1/activities/{activityId}/save`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| activityId | integer | Y | 활동 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "SAVED200_1",
  "message": "활동이 저장되었습니다.",
  "result": {
    "activityId": 1003,
    "isSaved": true,
    "savedAt": "2026-10-29T15:00:00+09:00"
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**ACTIVITY404_1**

HTTP 404 · 활동이 없거나 저장할 수 없음

```json
{
  "isSuccess": false,
  "code": "ACTIVITY404_1",
  "message": "활동이 없거나 저장할 수 없음",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activities` · `hobby_stages` · `hobbies` · `saved_activities`

**비고·정책**

- 요청 Body는 없다. 취미 나무를 심지 않은 활동도 저장할 수 있다.
- 저장은 북마크이며 활동 시작·나무 생성·성장 반영을 수행하지 않는다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 반복 저장도 현재 PUBLISHED+지원 취미 조건을 먼저 검사한다. 비공개/미지원이면 ACTIVITY404_1이며 기존 저장 관계·savedAt은 보존한다. 다시 지원되면 최초 시각으로 성공한다.
- 나무 보유/해금·콘텐츠 실행 단계 정비를 북마크 조건으로 추가하지 않는다. API-31~33의 ANONYMIZED 직접 서비스 호출 기본 거절 누락은 #249 후속이며 현재 HTTP 경로는 공통 필터가 차단한다.

## API-32 활동 저장 취소

### 로직 설명

1. 현재 회원의 activityId 저장 관계를 조회한다.
2. 관계가 있으면 삭제하고, 없으면 그대로 성공으로 처리한다.
3. 원본 활동이 비공개·미지원 취미·삭제 상태가 되었어도 저장 취소는 가능하도록 한다.

### API Endpoint

`DELETE /api/v1/activities/{activityId}/save`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| activityId | integer | Y | 활동 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "SAVED200_2",
  "message": "활동 저장이 취소되었습니다.",
  "result": {
    "activityId": 1003,
    "isSaved": false
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`saved_activities`

**비고·정책**

- 요청 Body는 없다. 양수 형식의 ID에 저장 관계가 없는 경우도 200을 반환한다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- 현재 회원의 저장 관계만 삭제한다. 미지원/비공개/없는 원본·미저장·반복 취소도200이며 다른 회원의 저장·활동 기록·성장은 바꾸지 않는다.
- 응답에는 savedAt이 없으며 조회 없이 권한이 완화되는 API가 아니다. JWT/ACTIVE 회원 조건은 유지하고 내부 익명화 회원 검사 보완은 #249에서 추적한다.

## API-33 저장한 활동 목록

### 로직 설명

1. 현재 회원의 저장 관계를 savedAt·ID 내림차순으로 조회한다.
2. 노출 가능한 활동의 상세 요약과 소속 취미·단계를 조합한다.
3. 비공개 또는 미지원 취미로 전환된 저장 항목은 이용 불가 안내만 반환해 저장 취소가 가능하게 한다.

### API Endpoint

`GET /api/v1/users/me/saved-activities`

### Query String

| Key | 타입 | 필수 | 기본값·제약 | 설명 |
| --- | --- | --- | --- | --- |
| hobbyId | integer | N | 양의 취미 ID | 특정 취미 저장 항목만 조회; 없는 ID는 빈 목록 |
| cursor | string | N | 서버 발급 커서 | 다음 페이지 |
| size | integer | N | 기본 20; 1~50 | 페이지 크기 |

요청 URL 예시:

```http
GET /api/v1/users/me/saved-activities?size=20
```

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

### Response-body · HTTP 200

```json
{
  "isSuccess": true,
  "code": "SAVED200_3",
  "message": "저장한 활동 목록을 조회했습니다.",
  "result": {
    "items": [
      {
        "activityId": 1003,
        "title": "명암 넣기",
        "thumbnailUrl": "https://cdn.example.com/hobbies/drawing.png",
        "durationMinutes": 30,
        "estimatedCost": 0,
        "difficulty": "BEGINNER",
        "indoorOutdoor": "INDOOR",
        "isSaved": true,
        "hobbyId": 10,
        "hobbyName": "드로잉",
        "stageId": 101,
        "stageNumber": 1,
        "savedAt": "2026-10-29T15:00:00+09:00",
        "isAvailable": true
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### Response-body — 이용 불가 항목 · HTTP 200

```json
{
  "isSuccess": true,
  "code": "SAVED200_3",
  "message": "저장한 활동 목록을 조회했습니다.",
  "result": {
    "items": [
      {
        "activityId": 1003,
        "savedAt": "2026-10-29T15:00:00+09:00",
        "isAvailable": false,
        "title": "이용할 수 없는 활동입니다"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`saved_activities` · `activities` · `hobbies` · `hobby_stages`

**비고·정책**

- 저장한 항목이 없으면 빈 items를 반환한다.
- 이용 불가 항목은 activityId·savedAt·isAvailable:false·안내 제목만 반환하고 원문·준비물 등 숨긴 콘텐츠는 노출하지 않는다.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- PUBLISHED+지원 취미는 요약14필드, 비공개/미지원은 activityId/savedAt/isAvailable:false/고정 안내 제목4필드만 반환한다. 숨긴 소속·이미지·요약을null 필드로도 노출하지 않는다.
- savedAt DESC, activityId DESC이며 커서는 사용자·hobbyId 필터에 묶인다. 저장 취소된 기준 행도 원래 키로 이어가고 API-24의 노출 순위 변경은 이 정렬에 영향을 주지 않는다.
- 유효하지만 없는 hobbyId는 빈 목록이고 빈/잘못된 hobbyId·size·서명 커서는 COMMON400_1이다. 나무 보유·해금과 관계없이 본인 북마크를 조회한다.
- 미상 요약값은null, 썸네일은 활동→취미→null이다. 활동 기록 삭제(API-50)는 북마크를 취소하지 않는다. 내부 익명화 회원 검사 보완은 #249 후속이다.

## API-50 활동 기록 삭제

### 로직 설명

1. JWT 인증과 ACTIVE 회원 여부를 확인하고, recordId가 본인 소유 나무의 활동 기록인지 확인한다. 타인 소유와 존재하지 않는 기록은 동일하게 404로 응답한다.
2. COMPLETED·PARTIAL·NOT_DONE으로 종료된 기록만 삭제할 수 있다. IN_PROGRESS는 409로 거절하며 API-29에서 먼저 완료 또는 중단 처리해야 한다. 본인 나무가 PAUSED·ENDED 상태여도 종료 기록 삭제는 허용한다.
3. 서버 시각으로 deletedAt을 저장하는 논리 삭제를 수행한다. 기록 ID·원래 종료 상태·시작 스냅샷·피드백·삭제 이력은 내부 정합성 검증용으로 보존하고, 사용자 활동 기록 조회와 새 코치 맥락 구성에서는 제외한다.
4. 삭제 대상이 COMPLETED이면 같은 나무·같은 활동의 남아 있는 비삭제 COMPLETED 기록을 확인한다. 하나라도 남아 있으면 고유 완료 활동 수와 성장 기여를 유지한다. 마지막 완료 기록이면 해당 (user_hobby_id, activity_id)의 성장 기여를 취소한다. PARTIAL·NOT_DONE 삭제는 성장에 영향을 주지 않는다.
5. 성장 기여를 취소하면 남은 유효 기여와 해당 취미의 기존 성장 규칙으로 나무 단계·진행률·다음 단계 목표를 다시 계산한다. 삭제로 단계가 내려갈 수 있다. 기록 삭제·기여 취소 또는 원본 참조 변경·나무 성장 갱신·삭제 결과 보관을 하나의 트랜잭션으로 처리한다.
6. 이미 삭제한 본인 기록에 같은 DELETE를 재요청하면 최초 삭제 결과를 그대로 반환한다. deletedAt을 바꾸거나 성장을 다시 차감하지 않는다. 삭제 완료 전에 요청이 실패하면 전체 변경을 롤백한다.

### API Endpoint

`DELETE /api/v1/activity-records/{recordId}`

### Path Parameter

| Key | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| recordId | integer | Y | 본인 소유의 종료된 활동 기록 ID; 양의 정수 |

### Query String

X

### Request Header

| Key | Value | 필수 | 설명 |
| --- | --- | --- | --- |
| Authorization | Bearer {accessToken} | Y | 서비스 Access Token |
| Accept | application/json | N | 응답 형식 |

### Request-body

X

요청 URL 예시:

```http
DELETE /api/v1/activity-records/401
```

### Response-body — 마지막 완료 기록 삭제 · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_6",
  "message": "활동 기록을 삭제했습니다.",
  "result": {
    "recordId": 401,
    "userHobbyId": 201,
    "activityId": 1003,
    "deleted": true,
    "deletedAt": "2026-11-18T17:00:00+09:00",
    "growth": {
      "growthRevoked": true,
      "distinctCompletedActivityCount": 2,
      "previousStageNumber": 2,
      "currentStageNumber": 1,
      "stageChanged": true,
      "isMaxStage": false
    }
  }
}
```

위 단계 값은 API-29의 예시 성장 규칙을 적용한 예시이며, 구현에서는 해당 취미에 적용된 기존 규칙을 사용한다. 삭제 API에서 별도의 단계 공식·활동 개수를 하드코딩하지 않는다.

### Response-body — 같은 활동의 다른 완료 기록이 남은 경우 · HTTP 200

```json
{
  "isSuccess": true,
  "code": "RECORD200_6",
  "message": "활동 기록을 삭제했습니다.",
  "result": {
    "recordId": 402,
    "userHobbyId": 201,
    "activityId": 1003,
    "deleted": true,
    "deletedAt": "2026-11-18T17:05:00+09:00",
    "growth": {
      "growthRevoked": false,
      "distinctCompletedActivityCount": 3,
      "previousStageNumber": 2,
      "currentStageNumber": 2,
      "stageChanged": false,
      "isMaxStage": false
    }
  }
}
```

| Key | 타입 | 설명 |
| --- | --- | --- |
| recordId | integer | 삭제한 기록 ID |
| userHobbyId | integer | 해당 기록의 본인 취미 나무 ID |
| activityId | integer | 해당 기록의 활동 ID |
| deleted | boolean | 삭제 성공 시 true |
| deletedAt | string(date-time) | 서버가 기록한 최초 삭제 시각; 재요청해도 유지 |
| growth.growthRevoked | boolean | 이번 삭제로 고유 활동의 성장 기여를 취소했는지 여부 |
| growth.distinctCompletedActivityCount | integer | 최초 삭제 처리 직후 해당 나무의 유효 고유 완료 활동 수 |
| growth.previousStageNumber | integer | 최초 삭제 처리 직전 나무 단계 |
| growth.currentStageNumber | integer | 최초 삭제 처리 직후 나무 단계 |
| growth.stageChanged | boolean | 삭제로 나무 단계가 변경됐는지 여부 |
| growth.isMaxStage | boolean | 최초 삭제 처리 직후 최대 단계인지 여부 |

PARTIAL·NOT_DONE 삭제는 growthRevoked:false이고 성장 수치·단계를 유지한다. 재요청 응답의 growth는 최초 삭제 당시 결과이므로, 이후 다른 활동으로 바뀐 최신 나무 현황은 API-20으로 재조회한다.

### Error

**COMMON400_1**

HTTP 400 · 필수값·JSON·필드 타입/범위·요청 형식 오류

```json
{
  "isSuccess": false,
  "code": "COMMON400_1",
  "message": "필수값·JSON·필드 타입/범위·요청 형식 오류",
  "result": null
}
```

**AUTH401_2**

HTTP 401 · Access Token 누락·만료·위조 또는 폐기된 세션

```json
{
  "isSuccess": false,
  "code": "AUTH401_2",
  "message": "Access Token 누락·만료·위조 또는 폐기된 세션",
  "result": null
}
```

**AUTH403_1**

HTTP 403 · 정지된 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_1",
  "message": "정지된 회원",
  "result": null
}
```

**AUTH403_3**

HTTP 403 · 탈퇴 회원의 접근

```json
{
  "isSuccess": false,
  "code": "AUTH403_3",
  "message": "탈퇴 회원의 접근",
  "result": null
}
```

**AUTH403_2**

HTTP 403 · 온보딩 미완료 회원

```json
{
  "isSuccess": false,
  "code": "AUTH403_2",
  "message": "온보딩 미완료 회원",
  "result": null
}
```

**RECORD404_1**

HTTP 404 · 본인 소유의 활동 기록을 찾을 수 없음

```json
{
  "isSuccess": false,
  "code": "RECORD404_1",
  "message": "본인 소유의 활동 기록을 찾을 수 없음",
  "result": null
}
```

**RECORD409_6**

HTTP 409 · 진행 중인 활동 기록은 삭제할 수 없음; API-29로 먼저 종료 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_6",
  "message": "진행 중인 활동 기록은 먼저 종료해 주세요.",
  "result": null
}
```

**RECORD409_7**

HTTP 409 · 완료 기록과 성장 기여의 정합성을 확인할 수 없어 삭제 중단; 데이터 정비 필요

```json
{
  "isSuccess": false,
  "code": "RECORD409_7",
  "message": "활동 기록과 성장 정보 확인이 필요합니다.",
  "result": null
}
```

**COMMON429_1**

HTTP 429 · 호출 제한 초과; Retry-After 헤더 참고 (목표 계약, 활동 API의 공통 제한은 현재 미구현)

```json
{
  "isSuccess": false,
  "code": "COMMON429_1",
  "message": "호출 제한 초과; Retry-After 헤더 참고",
  "result": null
}
```

**COMMON500_1**

HTTP 500 · 서버 내부 오류

```json
{
  "isSuccess": false,
  "code": "COMMON500_1",
  "message": "서버 내부 오류",
  "result": null
}
```

### 참고

**연관 테이블·저장소**

`activity_records(deleted_at)` · `activity_record_contents` · `activity_record_steps` · `activity_completion_receipts` · `activity_growth_contributions` · `activity_record_deletions` · `user_hobbies` · `hobbies`

**비고·정책**

- 2026-11-18 사용자 확정: 기록 삭제 시 성장 취소를 지원하되, 같은 나무·같은 활동의 비삭제 완료 기록이 하나라도 남으면 기여를 유지한다. 마지막 완료 기록을 삭제할 때만 기여를 취소한다. 다른 나무의 기록은 판단에 포함하지 않는다.
- 기존 API-01~49의 번호를 유지하기 위해 신규 API-50을 활동 도메인에 배치했다. 이 항목은 추가 명세이며 구현·DB 적용·운영 배포가 완료됐다는 의미가 아니다.
- 활동 완료는 기존 API-29가 상태·피드백·성장을 저장하는 처리이며, 이 API는 종료 후 기록 삭제를 담당한다. 챗봇에서 활동을 완료할 때 삭제 API로 대신 처리하지 않는다. 챗봇의 완료 기능에는 API-29 연동이 필요하며 삭제 기능은 별도 동작이다.
- 프론트는 삭제 전에 마지막 완료 기록 삭제 시 성장 기여가 취소되고 나무 단계가 내려갈 수 있음을 안내하고 사용자의 삭제 의사를 확인한다. 코치는 단순 완료 의사나 모델의 추측만으로 삭제를 실행하지 않으며, 명확히 선택한 recordId와 로그인 사용자의 권한으로 처리한다.
- 삭제는 원본 활동 카탈로그 삭제, API-32의 북마크 취소, 나무 종료, 예약·외부 참여 인정 취소와 구분한다. 해당 데이터의 상태를 자동으로 바꾸지 않는다.
- 삭제된 기록은 API-30의 모든 상태 필터·커서 페이지와 API-20의 최근 활동에서 제외한다. 삭제된 행을 먼저 제외한 뒤 페이지 크기·nextCursor·hasNext를 계산하며, 커서가 가리킨 기록이 삭제돼도 원래 정렬 기준으로 다음 페이지를 조회한다.
- 삭제된 기록의 API-27 조회, API-28 진행 저장, API-29 완료 재요청은 RECORD404_1로 응답한다. 소유권·삭제 여부를 먼저 확인하며 기존 완료 재전송 결과로 삭제를 되돌리거나 삭제된 피드백을 다시 노출하지 않는다. 본인 DELETE 재요청만 최초 삭제 결과로 성공 응답한다.
- 삭제한 기록·피드백은 새 코치 요청과 추천을 위한 활동 이력 조회에서 제외한다. 이미 저장된 대화 메시지와 과거 추천 응답을 소급 수정하는 기능은 이 API에 포함하지 않는다.
- 삭제 대상이 현재 성장 원장의 origin_record_id이고 다른 유효 완료 기록이 남으면, 남은 완료 기록 중 recordId가 가장 작은 기록으로 원본 참조를 넘겨 기여를 유지한다. 기존 기여의 인정 시각·출처와 최초 원본, 참조 변경 사유는 감사 이력으로 보존하며 후속 완료 기록이 최초 기여를 만든 것처럼 과거 응답을 고치지 않는다.
- 마지막 완료 기록 삭제 시 활성 성장 기여를 제거하고 취소 이력을 보존한다. 원장과 기록의 growth_counted·growth_stage_number 등 현재 집계용 표시는 함께 일치시키며, 삭제된 snapshot·종료 결과·과거 기여 사실은 보존한다. 기존 원본 FK를 깨뜨리는 물리 삭제나 승인 없는 과거 이력 보정은 하지 않는다.
- API-18·20·22의 성장 수치와 단계는 남아 있는 유효 원장을 기준으로 일치해야 한다. 취소 전 단계별 누적값을 그대로 사용해 잘못된 진행률을 반환하지 않도록 기존 집계와 검증 규칙도 함께 반영한다. 나무의 ACTIVE·PAUSED·ENDED 상태와 슬롯 수는 변경하지 않는다.
- 단계가 내려가면 신규 API-26 시작에는 낮아진 단계의 해금 조건을 적용한다. 이미 시작한 다른 기록은 스냅샷·체크·시각을 보존하고 기존 API-27~29 조건으로 이어갈 수 있으며, 이번 삭제가 다른 기록을 강제 종료하거나 삭제하지 않는다.
- 원장 기여가 취소된 활동을 API-26으로 새로 시작해 API-29에서 다시 완료하면, 당시 유효 기여가 없을 때 새 기여를 한 번 인정할 수 있다. 삭제된 recordId의 재전송은 재완료가 아니다. 같은 외부 참여 인정의 API-39 재전송만으로 삭제 기록을 복원하거나 취소 기여를 다시 생성하지 않도록 삭제·취소 이력을 확인한다.
- 인증 회원→본인 나무→활동 기록의 일관된 잠금 순서로 완료·삭제·성장 갱신을 직렬화한다. 동시 DELETE, 같은 활동의 서로 다른 완료 기록 삭제, 삭제와 새 완료가 경합해도 최종 비삭제 완료 기록과 활성 성장 기여가 일치해야 한다. 완료가 먼저 커밋되면 종료 상태에서 삭제하고, 삭제가 IN_PROGRESS 상태를 먼저 확인하면 RECORD409_6으로 거절한다.
- 과거 종료 기록도 소유권·종료 상태·성장 기여가 검증되면 삭제할 수 있다. 완료 기록과 원장이 이미 불일치하거나 기여 근거를 확인할 수 없으면 RECORD409_7로 전체 중단하고 임의 기여 생성·취소·백필 없이 별도 정비로 넘긴다. RECORD409_5는 API-26~29의 시작 스냅샷 부재 처리에 적용한다. API-50의 과거 종료 기록 삭제에는 스냅샷 존재를 요구하지 않으며 정합성 미확인은 RECORD409_7이다.
- 소스에는 V31의 삭제 표시·불변 삭제 결과/감사·현재 기여 배치와 서비스 조회 제외가 반영돼 develop에 병합됐다. 이는 운영 DB 적용·배포 완료를 뜻하지 않는다. 적용된 SQL을 수정하지 않으며 복구 API·물리 삭제·개인정보 보존 기간 변경은 포함하지 않는다.
- 필수 격리 검증: 소유권/회원 상태, 종료 상태별 삭제, 진행 중 거절, 재요청 무중복, 삭제 후 목록·상세·코치 이력 제외, 반복 완료의 마지막 기록 판단, 기여 원본 변경, 단계 하락과 기존 진행 보존, 외부 참여 재전송, 완료/삭제 동시성, 중간 실패 전체 롤백, 과거 데이터 불일치 중단.

**확정 정책 보완 · 2026-11-19**

- JWT 인증과 ACTIVE 회원이 필요하며 나무 보유·상태 조건은 각 API에서 별도로 정의한다.
- API-26~29/30/50은 develop에 구현·병합돼 있으나 운영 적용과 실제 계정 E2E 완료는 별도다. 삭제 응답은 최초 보관 결과이며 현재 나무는 API-20으로 재조회한다.
- 스냅샷 없는 과거 진행은 API-29로도409_5여서 자동 종료/삭제 우회가 없다. 진행 중은409_6이고 과거 종료의 성장 근거 불명은409_7이며 승인된 별도 정비가 필요하다.
- 현재 코치/추천은 activity_records를 직접 조회하지 않는다. 추후 이력 기반 요청을 연결할 때 deleted_at IS NULL을 적용해야 하며 코치 이력 제외 E2E를 이미 완료했다고 표시하지 않는다. 과거 메시지·추천 응답은 소급 변경하지 않는다.
- 남은 기여의 현재 단계 배치는 progress_stage_number로 관리하고 과거 credited_stage_number와 인정 출처/시각은 보존한다. 삭제 감사와 최초 완료/삭제 응답은 불변이다.

---
