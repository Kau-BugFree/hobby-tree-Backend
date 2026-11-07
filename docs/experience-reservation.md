# 예약 생성 분리 작업 (API-37)

이 작업은 #134 약관 PR에서 분리했으며 최신 develop(d4fe58c)의 활동 메타데이터 V18을 보존한다.
예약 스키마는 V19__add_experience_reservation_sessions.sql에서 변경한다.

## 정원 동시성

ExperienceSessionRepository.findForReservation은 PESSIMISTIC_WRITE로 세션 행을 잠근다.
정원 조회·검증과 예약 저장은 동일 트랜잭션 안에서 수행한다.
다른 사용자들이 마지막 좌석을 동시에 요청하면 먼저 잠금을 얻은 요청만 성공하고,
대기하던 요청은 커밋된 예약 수를 다시 조회하여 409로 거부한다.
취소 예약은 정원에서 제외한다.

## 기존 데이터 마이그레이션

기존 예약은 삭제하지 않는다. 일정이 정확히 하나인 경우에만 session_id를 백필한다.
일정이 없거나 여러 개인 경우 session_id를 null로 보존하며, REQUESTED 상태도 그대로 유지한다.
API-37의 신규 예약은 항상 session_id를 지정하고 REDIRECTED 또는 PENDING_VERIFICATION으로 생성한다.
일정을 확정할 수 없는 미취소 레거시 예약이 남은 클래스는 정원 누락을 피하기 위해 신규 예약을 거부한다.
운영 데이터가 있다면 관리자 검토로 일정을 연결한 뒤 신규 예약을 재개해야 한다.
provider_id는 기존 체험의 제공자 ID를 백필하며 외부 참조 중복 자료는 임의 삭제하지 않는다.

FlywayMigrationTests는 레거시 예약의 보존·단일 일정 백필 및 최신 Hibernate 스키마 검증을 실행한다.
ExperienceIntegrationTests는 서로 다른 사용자 요청이 실제 세션 행 잠금에 대기한 뒤
마지막 한 좌석에서 201/409로 나뉘고 예약이 1개만 남는지 확인한다.

실제 외부 예약·참여 확정·활동 성장 인정은 별도 범위이다.
