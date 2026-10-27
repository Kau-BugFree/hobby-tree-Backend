# HobbyTree 도메인 모델

루트 패키지: `com.hobbytree.hobbytree`. 스키마 기준은 `src/main/resources/db/migration/V1__init_schema.sql`이다.

| 패키지 | 역할 | Entity 파일 (각각 동일 이름의 Repository 생성) |
| --- | --- | --- |
| global | 기존 공통 설정, API 응답, 예외 처리 및 보안 설정 유지 | 기존 파일 유지 |
| domain.auth | 향후 소셜 로그인 및 JWT 인증을 위한 패키지 | 전용 테이블이 없어 Entity 없음 |
| domain.user | 회원, 선호, 지역, 위치 동의 이력 | User.java, UserPreference.java, UserLocation.java, LocationConsentHistory.java |
| domain.hobby | 취미 및 성장 단계 기준 정보 | Hobby.java, HobbyStage.java |
| domain.activity | 활동 콘텐츠, 단계, 준비물, 저장 | Activity.java, ActivityStep.java, ActivityMaterial.java, SavedActivity.java |
| domain.tree | 사용자 취미 나무 및 활동 수행 기록 | UserHobby.java, ActivityRecord.java |
| domain.recommendation | 추천 당시 선호 스냅샷 및 추천 항목 | Recommendation.java, RecommendationItem.java |
| domain.experience | 예약 가능한 체험과 예약 이력 | Experience.java, ExperienceReservation.java |
| domain.notification | 알림과 구독 이력 | UserNotification.java, NotificationSubscription.java |


## 매핑

- BIGINT → Long, SMALLINT → Short, INTEGER → Integer, NUMERIC(5,2) → BigDecimal.
- TIMESTAMPTZ → Instant. DB 시각을 사용하는 CreationTimestamp/UpdateTimestamp로 최초 시각 및 updated_at을 관리한다. 직접 SQL로 갱신하는 경우 updated_at 자동 갱신을 보장하지 않는다.
- JSONB → JSON 문자열(String) + JdbcTypeCode(SqlTypes.JSON). JSON 내부 구조를 임의로 제한하지 않는다.
- IDENTITY PK 17개는 GenerationType.IDENTITY를 사용한다. saved_activities는 SavedActivityId(userId, activityId)와 EmbeddedId/MapsId를 사용한다.
- FK는 단방향 LAZY 연관관계이며, UNIQUE FK는 OneToOne으로 매핑했다. SQL의 ON DELETE CASCADE/SET NULL은 Hibernate OnDelete에 반영했다. JPA CascadeType으로 연관 객체 생성/삭제를 전파하지 않는다.
- SQL에 명시된 CHECK와 UNIQUE를 반영했다. Enum은 STRING/VARCHAR로 저장하며 SQL의 문자열 값과 일치한다.
- notification_subscriptions의 부분 UNIQUE 인덱스는 일반 JPA UniqueConstraint로 표현하면 제약이 달라지므로 Flyway에만 둔다. 기타 조회 인덱스도 Flyway에서 관리한다.
- Entity는 Getter와 protected 기본 생성자만 제공한다. Setter, 임의 비즈니스 규칙, 생성 API는 추가하지 않았다.

## 미정 사항

- CHECK에 허용 값이 없는 hobby_status, notification_frequency, difficulty, notification_type 등의 문자열은 Enum으로 임의 제한하지 않았다.
- JSONB 내부 객체 형식, 각 Entity의 생성/변경 API, 위치 동의 보관 정책은 이후 요구사항 확정이 필요하다.
- SQL 주석의 나무 개수, 성장, 예약 정원, 참석 검증 등 트랜잭션 규칙은 이번 범위에 포함하지 않았다.

## 실제 검증

- Java 21 / Spring Boot 4.1.1에서 `gradlew.bat compileJava` 성공.
- `gradlew.bat check` 성공: 기존 테스트와 Spotless 검사 통과.
- 기존 PostgreSQL 17.11 컨테이너에 연결하여 Flyway 1개 migration 검증 성공. public 스키마 V1은 이미 적용되어 있어 추가 migration 없음.
- `ddl-auto=validate`를 유지한 채 EntityManagerFactory 초기화 및 Spring Boot 기동 성공. Repository 18개 탐색 확인.
- .env에 없는 DB_URL/DB_USERNAME은 compose.yml의 localhost:5433/hobbytree 및 hobbytree를 실행 프로세스 환경에만 지정했다. 기존 설정 파일은 수정하지 않았다.
- Hibernate validate는 CHECK, 부분 인덱스 등 모든 제약의 동등성을 검증하지 않는다. 해당 제약은 SQL 및 매핑 검토로 확인했으며, 제약 위반 INSERT/UPDATE 테스트는 수행하지 않았다. 테스트 데이터도 생성하지 않았다.

## 생성 파일 전체 목록

아래 경로는 저장소 루트 기준이다.
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/Activity.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/ActivityMaterial.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/ActivityStep.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/ReviewStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/SavedActivity.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/entity/SavedActivityId.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/repository/ActivityMaterialRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/repository/ActivityRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/repository/ActivityStepRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/activity/repository/SavedActivityRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/BookingMode.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/Experience.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/ExperienceReservation.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/ExperienceType.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/ListingStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/entity/ReservationStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/repository/ExperienceRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/experience/repository/ExperienceReservationRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/hobby/entity/Hobby.java`
- `src/main/java/com/hobbytree/hobbytree/domain/hobby/entity/HobbyStage.java`
- `src/main/java/com/hobbytree/hobbytree/domain/hobby/repository/HobbyRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/hobby/repository/HobbyStageRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/notification/entity/NotificationSubscription.java`
- `src/main/java/com/hobbytree/hobbytree/domain/notification/entity/SubscriptionType.java`
- `src/main/java/com/hobbytree/hobbytree/domain/notification/entity/UserNotification.java`
- `src/main/java/com/hobbytree/hobbytree/domain/notification/repository/NotificationSubscriptionRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/notification/repository/UserNotificationRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/recommendation/entity/Recommendation.java`
- `src/main/java/com/hobbytree/hobbytree/domain/recommendation/entity/RecommendationItem.java`
- `src/main/java/com/hobbytree/hobbytree/domain/recommendation/repository/RecommendationItemRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/recommendation/repository/RecommendationRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/entity/ActivityRecord.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/entity/ActivityStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/entity/TreeStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/entity/UserHobby.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/repository/ActivityRecordRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/tree/repository/UserHobbyRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/AccountStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/AuthProvider.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/ConsentAction.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/FirstBudgetStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/LocationConsentHistory.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/LocationStatus.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/User.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/UserLocation.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/UserPreference.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/entity/UserRole.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/repository/LocationConsentHistoryRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/repository/UserLocationRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/repository/UserPreferenceRepository.java`
- `src/main/java/com/hobbytree/hobbytree/domain/user/repository/UserRepository.java`
