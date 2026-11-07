package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.activity.service.ActivityDetailService;
import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityDetailIntegrationTests extends IntegrationTestSupport {
  @Autowired ActivityDetailService details;
  @Autowired ActivityRepository activityRepository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManagerFactory emf;
  @Autowired EntityManager em;

  @Test
  void returnsAllContractFieldsAndRealOrderedContentWithoutRequiringATree() throws Exception {
    var f = fixture(0);
    long second = step(f.activity(), 2, "두 번째", false);
    long first = step(f.activity(), 1, "첫 번째", true);
    long m1 = material(f.activity(), "연필");
    long m2 = material(f.activity(), "종이");
    jdbc.update(
        "UPDATE activities SET thumbnail_url='https://example.com/activity.png' WHERE activity_id=?",
        f.activity());
    var body = success(get(f.token(), f.activity()));
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "activityId",
            "title",
            "thumbnailUrl",
            "durationMinutes",
            "estimatedCost",
            "difficulty",
            "indoorOutdoor",
            "isSaved",
            "hobbyId",
            "stageId",
            "stageNumber",
            "description",
            "contentVersion",
            "materials",
            "steps",
            "cautions",
            "contentUpdatedAt");
    assertThat(body.path("activityId").asLong()).isEqualTo(f.activity());
    assertThat(body.path("hobbyId").asLong()).isEqualTo(f.hobby());
    assertThat(body.path("stageId").asLong()).isEqualTo(f.stage());
    assertThat(body.path("title").asText()).isEqualTo("실제 활동");
    assertThat(body.path("thumbnailUrl").asText()).endsWith("/activity.png");
    assertThat(body.path("description").asText()).isEqualTo("실제 상세 본문");
    assertThat(body.path("durationMinutes").asInt()).isEqualTo(35);
    assertThat(body.path("estimatedCost").asInt()).isEqualTo(1200);
    assertThat(body.path("difficulty").asText()).isEqualTo("BEGINNER");
    assertThat(body.path("indoorOutdoor").asText()).isEqualTo("OUTDOOR");
    assertThat(body.path("isSaved").asBoolean()).isFalse();
    assertThat(body.path("contentVersion").asLong()).isEqualTo(version(f.activity()));
    assertThat(Instant.parse(body.path("contentUpdatedAt").asText()))
        .isEqualTo(modified(f.activity()));
    assertThat(body.path("materials").get(0).propertyNames())
        .containsExactlyInAnyOrder(
            "materialId", "name", "quantity", "isRequired", "acquisitionType");
    assertThat(body.path("materials").get(0).path("materialId").asLong()).isEqualTo(m1);
    assertThat(body.path("materials").get(1).path("materialId").asLong()).isEqualTo(m2);
    assertThat(body.path("materials").get(0).path("quantity").asText()).isEqualTo("1개");
    assertThat(body.path("materials").get(0).path("acquisitionType").asText())
        .isEqualTo("OWN_OR_BUY");
    assertThat(body.path("materials").get(0).path("isRequired").asBoolean()).isTrue();
    var steps = body.path("steps");
    assertThat(steps.size()).isEqualTo(2);
    assertThat(steps.get(0).propertyNames())
        .containsExactlyInAnyOrder(
            "stepId", "stepNumber", "title", "description", "isRequired", "imageUrl");
    assertThat(steps.get(0).path("stepId").asLong()).isEqualTo(first);
    assertThat(steps.get(1).path("stepId").asLong()).isEqualTo(second);
    assertThat(steps.get(0).path("stepNumber").asInt()).isEqualTo(1);
    assertThat(steps.get(0).path("description").asText()).isEqualTo("실제 실행 지시");
    assertThat(steps.get(0).path("isRequired").asBoolean()).isTrue();
    assertThat(steps.get(1).path("isRequired").asBoolean()).isFalse();
    assertThat(steps.get(0).path("imageUrl").isNull()).isTrue();
    assertThat(body.path("cautions").size()).isEqualTo(1);
    assertThat(body.path("cautions").get(0).asText()).isEqualTo("주의 1\n주의 2");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_hobbies WHERE user_id=?", Long.class, f.user()))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void previewsEveryStageIncludingLockedStagesUsingExternalNumbers(int stored) throws Exception {
    var f = fixture(stored);
    assertThat(success(get(f.token(), f.activity())).path("stageNumber").asInt())
        .isEqualTo(stored + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT stage_number FROM hobby_stages WHERE hobby_stage_id=?",
                Integer.class,
                f.stage()))
        .isEqualTo(stored);
  }

  @Test
  void keepsEmptyArraysAndUnknownLegacyValuesRatherThanInventingContent() throws Exception {
    var f = fixture(0);
    jdbc.update(
        "UPDATE activities SET detailed_description=NULL,safety_notes=NULL,expected_total_minutes=NULL,estimated_cost_min=NULL,difficulty=NULL WHERE activity_id=?",
        f.activity());
    jdbc.update("UPDATE hobbies SET thumbnail_url=NULL WHERE hobby_id=?", f.hobby());
    var empty = success(get(f.token(), f.activity()));
    for (String a : List.of("steps", "materials", "cautions"))
      assertThat(empty.path(a).isArray() && empty.path(a).isEmpty()).isTrue();
    for (String n :
        List.of("description", "thumbnailUrl", "durationMinutes", "estimatedCost", "difficulty")) {
      assertThat(empty.has(n)).isTrue();
      assertThat(empty.path(n).isNull()).isTrue();
    }
    jdbc.update(
        "INSERT INTO activity_steps(activity_id,step_number,instruction) VALUES (?,1,'원본 지시')",
        f.activity());
    jdbc.update(
        "INSERT INTO activity_materials(activity_id,material_name) VALUES (?,'원본 준비물')",
        f.activity());
    var legacy = success(get(f.token(), f.activity()));
    for (String n : List.of("title", "isRequired", "imageUrl")) {
      assertThat(legacy.path("steps").get(0).has(n)).isTrue();
      assertThat(legacy.path("steps").get(0).path(n).isNull()).isTrue();
    }
    assertThat(legacy.path("materials").get(0).path("acquisitionType").isNull()).isTrue();
    assertThat(legacy.path("materials").get(0).path("quantity").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "REVIEW", "REJECTED"})
  void rejectsNonPublishedActivities(String review) throws Exception {
    var f = fixture(0);
    jdbc.update("UPDATE activities SET review_status=? WHERE activity_id=?", review, f.activity());
    error(get(f.token(), f.activity()), 404, "ACTIVITY404_1");
  }

  @Test
  void rejectsPrivateHobbyAndMissingActivity() throws Exception {
    var f = fixture(0);
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    error(get(f.token(), f.activity()), 404, "ACTIVITY404_1");
    error(get(f.token(), Long.MAX_VALUE), 404, "ACTIVITY404_1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "text", "9223372036854775808"})
  void rejectsInvalidIds(String id) throws Exception {
    var f = fixture(0);
    error(request(f.token(), "/api/v1/activities/" + id), 400, "COMMON400_1");
  }

  @ParameterizedTest
  @CsvSource({"PENDING_ONBOARDING,AUTH403_2", "SUSPENDED,AUTH403_1", "WITHDRAWN,AUTH403_3"})
  void rejectsNonActiveMembers(String status, String code) throws Exception {
    var f = fixture(0);
    jdbc.update(
        """
        UPDATE users SET account_status=?, withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() END,
          restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+INTERVAL '720 hours' END,
          status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' END WHERE user_id=?
        """,
        status,
        status,
        status,
        status,
        f.user());
    error(get(f.token(), f.activity()), 403, code);
  }

  @Test
  void rejectsMissingForgedExpiredAndRevokedTokens() throws Exception {
    var f = fixture(0);
    error(get(null, f.activity()), 401, "AUTH401_2");
    error(get("forged.jwt.token", f.activity()), 401, "AUTH401_2");
    var old =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC));
    String expired =
        old.issue(
            users.findById(f.user()).orElseThrow(),
            decoder.decode(f.token()).getClaimAsString("sid"),
            "access");
    error(get(expired, f.activity()), 401, "AUTH401_2");
    redis.delete("auth:session:" + f.user());
    error(get(f.token(), f.activity()), 401, "AUTH401_2");
  }

  @Test
  void savedStateIsPersonalAndSaveUnsaveDoesNotChangeTheContentVersion() throws Exception {
    var f = fixture(0);
    setup();
    var other = fixture(0);
    long v = version(f.activity());
    Instant t = modified(f.activity());
    var save =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/activities/"
                            + f.activity()
                            + "/save"))
                .header("Authorization", "Bearer " + f.token())
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(save.statusCode()).as(save.body()).isEqualTo(200);
    assertThat(success(get(f.token(), f.activity())).path("isSaved").asBoolean()).isTrue();
    assertThat(success(get(other.token(), f.activity())).path("isSaved").asBoolean()).isFalse();
    var unsave =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/activities/"
                            + f.activity()
                            + "/save"))
                .header("Authorization", "Bearer " + f.token())
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unsave.statusCode()).as(unsave.body()).isEqualTo(200);
    assertThat(success(get(f.token(), f.activity())).path("isSaved").asBoolean()).isFalse();
    assertThat(version(f.activity())).isEqualTo(v);
    assertThat(modified(f.activity())).isEqualTo(t);
  }

  @Test
  void api24And25UseTheSameImagePriorityAndSummaryFields() throws Exception {
    var f = fixture(0);
    for (int mode = 0; mode < 3; mode++) {
      if (mode == 1)
        jdbc.update(
            "UPDATE activities SET thumbnail_url='https://example.com/activity.png' WHERE activity_id=?",
            f.activity());
      if (mode == 2) {
        jdbc.update("UPDATE activities SET thumbnail_url=NULL WHERE activity_id=?", f.activity());
        jdbc.update("UPDATE hobbies SET thumbnail_url=NULL WHERE hobby_id=?", f.hobby());
      }
      var detail = success(get(f.token(), f.activity()));
      var list =
          result(request(f.token(), "/api/v1/hobby-stages/" + f.stage() + "/activities"))
              .path("items")
              .get(0);
      for (String field :
          List.of(
              "activityId",
              "title",
              "thumbnailUrl",
              "durationMinutes",
              "estimatedCost",
              "difficulty",
              "indoorOutdoor",
              "isSaved")) assertThat(detail.path(field)).as(field).isEqualTo(list.path(field));
    }
  }

  @Test
  void coalescesRootAndManyChildChangesOncePerTransactionButIgnoresNoOpsAndMetadata()
      throws Exception {
    var f = fixture(0);
    long s = step(f.activity(), 1, "제목", true), m = material(f.activity(), "준비물");
    long before = version(f.activity());
    Instant time = modified(f.activity());
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        x -> {
          jdbc.update(
              "UPDATE activities SET activity_name='새 제목' WHERE activity_id=?", f.activity());
          jdbc.update("UPDATE activity_steps SET instruction='새 본문' WHERE activity_step_id=?", s);
          jdbc.update(
              "UPDATE activity_materials SET quantity_description='2개' WHERE activity_material_id=?",
              m);
          jdbc.update(
              "INSERT INTO activity_steps(activity_id,step_number,instruction,title,is_required) VALUES (?,2,'다음','다음',false)",
              f.activity());
          assertThat(version(f.activity())).isEqualTo(before + 1);
        });
    assertThat(version(f.activity())).isEqualTo(before + 1);
    assertThat(modified(f.activity())).isAfter(time);
    Instant after = modified(f.activity());
    jdbc.update(
        "UPDATE activities SET activity_name=activity_name,updated_at=clock_timestamp(),reviewed_at=clock_timestamp() WHERE activity_id=?",
        f.activity());
    jdbc.update("UPDATE activity_steps SET title=title WHERE activity_step_id=?", s);
    jdbc.update(
        "UPDATE activity_materials SET material_name=material_name WHERE activity_material_id=?",
        m);
    assertThat(version(f.activity())).isEqualTo(before + 1);
    assertThat(modified(f.activity())).isEqualTo(after);
    tx.executeWithoutResult(
        x -> {
          jdbc.update("DELETE FROM activity_steps WHERE activity_step_id=?", s);
          jdbc.update("DELETE FROM activity_materials WHERE activity_material_id=?", m);
          x.setRollbackOnly();
        });
    assertThat(version(f.activity())).isEqualTo(before + 1);
    assertThat(modified(f.activity())).isEqualTo(after);
    jdbc.update("DELETE FROM activity_steps WHERE activity_step_id=?", s);
    assertThat(version(f.activity())).isEqualTo(before + 2);
    jdbc.update("DELETE FROM activity_materials WHERE activity_material_id=?", m);
    assertThat(version(f.activity())).isEqualTo(before + 3);
  }

  @Test
  void inheritedImagesOnlyTouchFallbackActivitiesAndIndoorMetadataTouchesBoth() throws Exception {
    var f = fixture(0);
    long other = activity(f.stage());
    jdbc.update(
        "UPDATE activities SET thumbnail_url='https://example.com/own.png' WHERE activity_id=?",
        other);
    long a = version(f.activity()), b = version(other);
    jdbc.update(
        "UPDATE hobbies SET thumbnail_url='https://example.com/new.png' WHERE hobby_id=?",
        f.hobby());
    assertThat(version(f.activity())).isEqualTo(a + 1);
    assertThat(version(other)).isEqualTo(b);
    jdbc.update("UPDATE hobbies SET indoor_outdoor='INDOOR' WHERE hobby_id=?", f.hobby());
    assertThat(version(f.activity())).isEqualTo(a + 2);
    assertThat(version(other)).isEqualTo(b + 1);
  }

  @Test
  void detailReadsOneRepeatableReadSnapshotAcrossConcurrentAggregateEdit() throws Exception {
    var f = fixture(0);
    step(f.activity(), 1, "기존 단계", true);
    long before = version(f.activity());
    var tx = new TransactionTemplate(transactionManager);
    tx.setReadOnly(true);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try (var pool = Executors.newSingleThreadExecutor()) {
      tx.executeWithoutResult(
          x -> {
            assertThat(version(f.activity())).isEqualTo(before); // establish the reader snapshot
            try {
              pool.submit(
                      () ->
                          new TransactionTemplate(transactionManager)
                              .executeWithoutResult(
                                  y -> {
                                    jdbc.update(
                                        "UPDATE activities SET activity_name='경쟁 수정' WHERE activity_id=?",
                                        f.activity());
                                    jdbc.update(
                                        "UPDATE activity_steps SET title='경쟁 단계' WHERE activity_id=?",
                                        f.activity());
                                  }))
                  .get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
            var snapshot = details.detail(f.user(), f.activity());
            assertThat(snapshot.contentVersion()).isEqualTo(before);
            assertThat(snapshot.title()).isEqualTo("실제 활동");
            assertThat(snapshot.steps().getFirst().title()).isEqualTo("기존 단계");
          });
    }
    var fresh = success(get(f.token(), f.activity()));
    assertThat(fresh.path("contentVersion").asLong()).isEqualTo(before + 1);
    assertThat(fresh.path("title").asText()).isEqualTo("경쟁 수정");
    assertThat(fresh.path("steps").get(0).path("title").asText()).isEqualTo("경쟁 단계");
  }

  @Test
  void jpaSeesDbGeneratedVersionAndTimestampAndRefreshesAfterChildFlush() throws Exception {
    var f = fixture(0);
    step(f.activity(), 1, "원본", true);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            x -> {
              Activity entity = activityRepository.findById(f.activity()).orElseThrow();
              long before = entity.getContentVersion();
              // JDBC participates in this transaction; child triggers do not automatically refresh
              // JPA.
              jdbc.update("UPDATE activity_steps SET title='수정' WHERE activity_id=?", f.activity());
              em.flush();
              em.refresh(entity);
              assertThat(entity.getContentVersion()).isEqualTo(before + 1);
              assertThat(entity.getContentUpdatedAt()).isEqualTo(modified(f.activity()));
            });
  }

  @Test
  void fixedQueryCountDoesNotGrowWithMaterialsOrSteps() throws Exception {
    var f = fixture(0);
    step(f.activity(), 1, "1", true);
    material(f.activity(), "1");
    var stats = emf.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    try {
      long start = stats.getPrepareStatementCount();
      success(get(f.token(), f.activity()));
      long small = stats.getPrepareStatementCount() - start;
      for (int i = 2; i <= 30; i++) {
        step(f.activity(), i, "단계" + i, true);
        material(f.activity(), "준비물" + i);
      }
      start = stats.getPrepareStatementCount();
      var body = success(get(f.token(), f.activity()));
      assertThat(body.path("steps").size()).isEqualTo(30);
      assertThat(body.path("materials").size()).isEqualTo(30);
      assertThat(stats.getPrepareStatementCount() - start).isEqualTo(small);
      assertThat(small).isPositive().isLessThanOrEqualTo(8);
    } finally {
      stats.setStatisticsEnabled(enabled);
    }
  }

  @Test
  void jpaInsertAndUpdateReadGeneratedMetadataWithoutAssigningFakeVersions() throws Exception {
    var f = fixture(0);
    Long id =
        new TransactionTemplate(transactionManager)
            .execute(
                x -> {
                  var entity = org.springframework.beans.BeanUtils.instantiateClass(Activity.class);
                  org.springframework.test.util.ReflectionTestUtils.setField(
                      entity,
                      "hobbyStage",
                      em.getReference(
                          com.hobbytree.hobbytree.domain.hobby.entity.HobbyStage.class, f.stage()));
                  org.springframework.test.util.ReflectionTestUtils.setField(
                      entity, "activityCode", UUID.randomUUID().toString());
                  org.springframework.test.util.ReflectionTestUtils.setField(
                      entity, "activityName", "JPA 생성");
                  em.persist(entity);
                  em.flush();
                  assertThat(entity.getContentVersion()).isEqualTo(1L);
                  assertThat(entity.getContentUpdatedAt()).isNotNull();
                  return entity.getActivityId();
                });
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            x -> {
              var entity = em.find(Activity.class, id);
              org.springframework.test.util.ReflectionTestUtils.setField(
                  entity, "activityName", "JPA 수정");
              em.flush();
              assertThat(entity.getContentVersion()).isEqualTo(2L);
              assertThat(entity.getContentUpdatedAt()).isEqualTo(modified(id));
            });
  }

  @Test
  void parentShareLockKeepsFutureStartSnapshotStableUntilChildEditorCommits() throws Exception {
    var f = fixture(0);
    step(f.activity(), 1, "기존 단계", true);
    long before = version(f.activity());
    try (var reader = dataSource.getConnection();
        var writer = dataSource.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      reader.setAutoCommit(false);
      writer.setAutoCommit(false);
      try {
        try (var statement = reader.createStatement()) {
          statement
              .executeQuery(
                  "SELECT activity_id FROM activities WHERE activity_id="
                      + f.activity()
                      + " FOR SHARE")
              .close();
        }
        int pid;
        try (var statement = writer.createStatement();
            var row = statement.executeQuery("SELECT pg_backend_pid()")) {
          row.next();
          pid = row.getInt(1);
        }
        var update =
            pool.submit(
                () -> {
                  try (var statement = writer.createStatement()) {
                    statement.executeUpdate(
                        "UPDATE activity_steps SET title='변경 단계' WHERE activity_id="
                            + f.activity());
                    writer.commit();
                    return true;
                  }
                });
        boolean blocked = false;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",
                      Boolean.class,
                      pid));
          if (blocked) break;
          Thread.sleep(20);
        }
        assertThat(blocked).as("child revision waits for parent SHARE lock").isTrue();
        try (var statement = reader.createStatement();
            var row =
                statement.executeQuery(
                    "SELECT title FROM activity_steps WHERE activity_id=" + f.activity())) {
          row.next();
          assertThat(row.getString(1)).isEqualTo("기존 단계");
        }
        reader.commit();
        assertThat(update.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(version(f.activity())).isEqualTo(before + 1);
      } finally {
        reader.rollback();
        writer.rollback();
      }
    }
  }

  @Test
  void concurrentEditorsCommitTwoDistinctRevisionsWithoutLosingEitherChildChange()
      throws Exception {
    var f = fixture(0);
    long first = step(f.activity(), 1, "원본 1", true), second = step(f.activity(), 2, "원본 2", true);
    long before = version(f.activity());
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (long id : List.of(first, second)) {
        futures.add(
            pool.submit(
                () ->
                    new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                            tx -> {
                              ready.countDown();
                              try {
                                if (!start.await(5, TimeUnit.SECONDS))
                                  throw new IllegalStateException("edit barrier timeout");
                              } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(ex);
                              }
                              jdbc.update(
                                  "UPDATE activity_steps SET title=? WHERE activity_step_id=?",
                                  "수정 " + id,
                                  id);
                            })));
      }
      try {
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      for (var future : futures) future.get(10, TimeUnit.SECONDS);
    }
    assertThat(version(f.activity())).isEqualTo(before + 2);
    var body = success(get(f.token(), f.activity()));
    assertThat(body.path("steps").get(0).path("title").asText()).isEqualTo("수정 " + first);
    assertThat(body.path("steps").get(1).path("title").asText()).isEqualTo("수정 " + second);
  }

  private Fixture fixture(int stageNumber) throws Exception {
    var response = login();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    long user = result(response).path("userId").asLong();
    createdUsers.add(user);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", user);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,thumbnail_url,indoor_outdoor) VALUES (?,'실제 취미','https://example.com/hobby.png','OUTDOOR') RETURNING hobby_id",
            Long.class,
            "profile-test-" + user + "-" + UUID.randomUUID().toString().substring(0, 12));
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,'실제 성장 단계') RETURNING hobby_stage_id",
            Long.class,
            hobby,
            stageNumber);
    return new Fixture(
        user, result(response).path("accessToken").asText(), hobby, stage, activity(stage));
  }

  private long activity(long stage) {
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,short_description,detailed_description,review_status,expected_active_minutes,expected_total_minutes,estimated_cost_min,estimated_cost_max,difficulty,safety_notes) VALUES (?,?,'실제 활동','짧은 소개','실제 상세 본문','PUBLISHED',10,35,1200,5000,'BEGINNER',?) RETURNING activity_id",
        Long.class,
        stage,
        UUID.randomUUID().toString(),
        "주의 1\n주의 2");
  }

  private long step(long a, int n, String title, boolean required) {
    return jdbc.queryForObject(
        "INSERT INTO activity_steps(activity_id,step_number,instruction,title,is_required) VALUES (?,?,'실제 실행 지시',?,?) RETURNING activity_step_id",
        Long.class,
        a,
        n,
        title,
        required);
  }

  private long material(long a, String name) {
    return jdbc.queryForObject(
        "INSERT INTO activity_materials(activity_id,material_name,quantity_description,is_required,acquisition_type) VALUES (?,?,'1개',true,'OWN_OR_BUY') RETURNING activity_material_id",
        Long.class,
        a,
        name);
  }

  private long version(long id) {
    return jdbc.queryForObject(
        "SELECT content_version FROM activities WHERE activity_id=?", Long.class, id);
  }

  private Instant modified(long id) {
    return jdbc.queryForObject(
            "SELECT content_updated_at FROM activities WHERE activity_id=?",
            java.sql.Timestamp.class,
            id)
        .toInstant();
  }

  private HttpResponse<String> get(String token, long id) throws Exception {
    return request(token, "/api/v1/activities/" + id);
  }

  private HttpResponse<String> request(String token, String path) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (token != null) b.header("Authorization", "Bearer " + token);
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var body = mapper.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("ACTIVITY200_3");
    assertThat(body.path("message").asText()).isEqualTo("활동 상세를 조회했습니다.");
    return body.path("result");
  }

  private void error(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo(code);
  }

  @Override
  protected void cleanupSeededHobbies(long id) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)",
        "profile-test-" + id + "-%");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN (SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h USING(hobby_id) WHERE h.hobby_code LIKE ?)",
        "profile-test-" + id + "-%");
    super.cleanupSeededHobbies(id);
  }

  private record Fixture(long user, String token, long hobby, long stage, long activity) {}
}
