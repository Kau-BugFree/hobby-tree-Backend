# 프로필 이미지 S3 연결

API-08 업로드 URL의 설정 누락은 HTTP 503 UPLOAD503_1로 반환한다.
프로필 API-05·06의 profileImageUrl은 최종 객체의 15분 GET 서명 URL이다.
기본 이미지인 경우 null이다. URL과 쿼리 문자열은 로그·분석 이벤트에 기록하지 않는다.
앱은 URL을 영구 저장하지 않고, 이미지 요청이 403이면 프로필을 재조회해 한 번 갱신한다.
업로드는 API-08이 반환한 PUT URL과 Content-Type으로 전송한 뒤 API-06에 objectKey를 연결한다.

## 확인한 운영 현황

2026-11-28 조회에서 자산 버킷 후보는 hobbytree-dev-assets-494915081306,
리전은 ap-northeast-2다. profiles/ 접두사에는 현재 객체가 없었다.
버킷은 공개 차단 상태이고 기본 암호화는 SSE-S3다. 버킷 CORS는 미설정이다.
백엔드 hobbytree-ec2-role에는 hobbies/* GetObject만 있으며,
profiles/* PutObject·GetObject·DeleteObject 시뮬레이션은 모두 implicitDeny였다.
이 버킷의 profiles/ 사용은 다음 운영 승인에 포함한다.

## 운영 적용 승인안

1. 백엔드 역할에 해당 버킷의 profiles/* 객체 PutObject·GetObject·DeleteObject 범위를 추가한다.
   서명 업로드와 최종 복사에 쓰기, 검증·다운로드 서명에 읽기, 기존 정리 작업에 삭제가 필요하다.
2. Expo Web 업로드에는 합의한 localhost:8081 및 실제 웹 출처의 PUT CORS와
   Content-Type 허용 및 GET CORS이 필요하다. 네이티브 앱과 웹을 각각 검증한다.
3. 보호된 배포 환경 파일에 아래 공개 식별자를 추가하고 검증한다.
   PROFILE_IMAGE_S3_BUCKET=hobbytree-dev-assets-494915081306
   PROFILE_IMAGE_S3_REGION=ap-northeast-2
4. 설정 반영 A와 이미지 교체 B를 같은 유지보수 구간에서 실행한다. A에서 현재 이미지 digest와
   컨테이너·마운트·환경·보호 해시를 기록하고 ALB 대상 제외 및 자동 복구 경합 방지 후,
   변경 허용 목록을 두 S3 설정으로 제한한다. 현재 digest를 BACKEND_IMAGE에 고정한 채
   docker compose --env-file /opt/hobbytree/.env.ec2 -f /opt/hobbytree/compose.ec2.yml up -d --no-deps --force-recreate backend
   로 설정을 반영하고 health·컨테이너의 버킷/리전 일치 및 이미지 불변을 확인한다.
   기록한 설정과 허용 목록 외의 차이는 중단 사유다.
   그 후 배포 도구·Compose·환경 파일 보호 해시와 자동 복구 기준을 검토한 값으로 갱신한다.
   B에서 승인된 main 이미지를 기존 이미지 교체 실행기로 배포하고 검증 후 ALB·자동 복구를 복원한다.
   설정이 다른 상태에서는 B 검증을 우회하지 않는다. A 실패 시 이전 환경·Compose로 현재 이미지를 재생성한다.
5. 사용자 승인된 시연 계정으로 URL 발급→PUT→연결→프로필 조회→GET과
   초기화·후속 정리를 확인한다. 이 검증은 S3·DB 쓰기 승인이 필요하다.

정책 초안은 다음 정적 분석 명령으로 생성한 뒤 profiles/* 범위를 대조한다.

```powershell
uvx iam-policy-autopilot@latest generate-policies src/main/java/com/hobbytree/hobbytree/domain/profileimage/service/ProfileImageStorageGateway.java --region ap-northeast-2 --account 494915081306 --service-hints s3 --pretty
```

배포 중단 예상은 사전점검 후 기존 V33 이미지 교체 경로 기준 5~15분이다.
실패하면 기존 이미지·환경 파일·보호 해시와 IAM·CORS 변경 전 상태로 복구한다.
기존 이미지의 사진 변경 기능은 원래 설정 누락 상태였으므로 별도로 검증한다.
스키마 변경은 없다. 운영 적용 결과는 승인 후 기록한다.
