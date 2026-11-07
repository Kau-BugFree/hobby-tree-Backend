# 내부 0~5 / 외부 1~6 단계 계약

상태: API-18~21 외부 응답 1~6 전환을 로컬 구현했다.
기준 develop: 4fa824a484907140c18e838060b2a8c518008133 (API-21 PR #97 병합).
노션 명세를 최우선으로 사용하며 API-18~21을 함께 전환한다.

## 구현 경계

DB·JPA·성장 판정은 0~5, HTTP 응답 currentStageNumber는 1~6이다.
공통 domain.hobby.dto.ApiStageNumber.fromStored(short)는 저장 범위 0~5를 검증하고 +1한다.
잘못된 저장 번호는 IllegalArgumentException으로 거절한다. 사용자 입력 검증기가 아니다.
이미 변환한 응답 번호를 인자로 전달하지 않는다. DTO 생성자와 직렬화기는 재변환하지 않는다.
UserHobbyService의 plant·changeStatus·toItem 세 응답 매핑 지점에서만 호출한다.
API-20 detail은 API-18 toItem의 변환된 번호를 그대로 복사하므로 이중 +1하지 않는다.

| 저장 번호 | 외부 번호 | 이름 조회 | 최고 단계 |
|---|---|---|---|
| 0 | 1 | DB stage_number=0 | false |
| 1~4 | 2~5 | 해당 저장 stage_number | false |
| 5 | 6 | DB stage_number=5 | true |

단계 이름은 저장 번호에서 먼저 조회한다. 변환기가 단계·이름을 생성하지 않는다.
정의 누락 시 currentStageName=null, 최고 단계 다음 목표 null·남은 수0을 유지한다.
totalStages=6은 개수다. 내부 최고 번호5·외부 최고 번호6과 구분한다.

## 적용 API와 JSON 경로

| API | 외부 JSON 경로 | 적용 방식 |
|---|---|---|
| 18 GET /api/v1/user-hobbies | result.items[].currentStageNumber | toItem에서 한 번 변환 |
| 19 POST /api/v1/user-hobbies | result.currentStageNumber | DB0 생성 유지·응답1 |
| 20 GET /api/v1/user-hobbies/{userHobbyId} | result.currentStageNumber | 변환된 Item값 그대로 복사 |
| 21 PATCH /api/v1/user-hobbies/{userHobbyId}/status | result.currentStageNumber | 저장 번호에서 한 번 변환 |

API-16 HobbyItemResponse에는 단계 번호가 없다. 입문활동 조회는 DB stage_number=0을 유지한다.
API-17에도 외부 단계 번호가 없으므로 변경하지 않는다. API-17 totalStages는 실제 등록 단계 수,
API-20 totalStages는 정책상6이라는 기존 의미 차이도 이번 범위에서 변경하지 않는다.

변환하지 않는 값: stageId, hobbyId, activityId, stepId, recordId, 실행 stepNumber,
활동 개수·누적 기여·목표 활동 수·unlock 임계값·슬롯 수·totalStages.
Entity getter·전역 숫자 serializer·DB stage_number/current_stage_number/growth_stage_number,
Flyway V1~V13·기본값·CHECK·API-21 updatedAt 트리거를 변경하지 않는다.
성장 판정·단계 이름 조회·API-19/21 잠금과 최대5그루·상태별 시간·API-20 progressPercent는 유지한다.

## 후속 API 규칙

| API | 외부 번호 경로 | 규칙 |
|---|---|---|
| 22 | result.currentStageNumber, result.stages[].stageNumber | 둘 다 같은 변환기 사용 |
| 23 | result.stages[].stageNumber | 같은 변환기 사용 |
| 24 | result.stageNumber | 같은 변환기 사용; Path stageId는 그대로 사용 |
| 25 | result.stageNumber | 같은 변환기 사용; activityId·stageId·stepNumber는 그대로 사용 |
| 29 | result.growth.previousStageNumber/currentStageNumber | 이전·이후 함께 변환; 저장 결과0~5 유지 |
| 33 | result.items[].stageNumber | 같은 변환기 사용; hobbyId 필터 불변 |
| 39 | result.growth.previousStageNumber/currentStageNumber | API-29와 같은 변환 규칙 |

API-26~28에는 원문 기준 외부 성장 번호 필드가 없다. 활동 시작 가능 단계·잠금은 내부 값으로
판정한다. 현재 원문49개에는 stageNumber 요청 필터가 없으며 API-24의 Path는 stageId다.
새 필터를 임의로 추가하지 않는다. 향후 계약에 외부 번호 입력이 추가되면 1~6 검증 후 -1로
역변환한다. 이번 작업에는 필요하지 않은 역변환 함수를 추가하지 않는다.

저장 단계n·단계당 목표k의 누적 해제 목표는 n*k, 외부번호d 기준 (d-1)*k다.
COMPLETED/CURRENT/LOCKED 및 최고 단계 판정은 내부 번호에서 계산한다. 활동 수에 +1하지 않는다.
API-18/20의 현재 단계별 기여와 API-22/29의 누적 성장 정책 정합성은 별도 후속 작업이며,
번호 변환만으로 해결됐다고 간주하지 않는다. 기존 기여·기록을 재배정하지 않는다.

## FE 이행

FE가 이미 표시용 +1을 적용하면 제거한다. 0부터 시작하는 배열·이미지 인덱스는 외부번호에서
명시적으로 1을 빼서 사용한다. 신규1·최고6으로 비교하고 stage===0, stage===5, falsy 분기,
진행 바·switch·파일명·캐시·목업·스토리북을 점검한다. ID에는 변환을 적용하지 않는다.
FE와 BE의 릴리스 시점을 맞추며 별도 합의 없이 버전·헤더·응답 필드를 추가하지 않는다.
최신 FE 코드의 수정 여부는 이번 백엔드 작업으로 확인하지 않았다. 실제 배포는 별도 단계다.

## 검증 기준

저장0→외부1·저장5→외부6·모든 중간 단계, API-19 생성→18 목록→20 상세→21 상태의
번호 일치와 이중 변환 방지, 같은 저장 단계의 이름·ID·성장 기여·목표·최고 판정 보존을 검증한다.
내부 저장값 검증은 유지하고 외부 응답 기대값만 노션에 맞춘다. 상태·심기 경쟁·updatedAt,
API-16/17 및 전체 통합·Flyway 회귀를 실제 격리 PostgreSQL17·Redis에서 확인한다.

이 전환은 API-18~21의 단계 번호 계약 차이만 해소한다. 공통 오류·진행률·성장 원장 등
다른 계약 차이가 해소됐다는 의미가 아니다. DB 마이그레이션은 필요 없다.
