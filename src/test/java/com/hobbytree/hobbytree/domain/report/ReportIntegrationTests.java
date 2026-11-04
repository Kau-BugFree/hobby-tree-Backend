package com.hobbytree.hobbytree.domain.report;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ReportIntegrationTests extends IntegrationTestSupport {
  Long hobbyId;
  Long stageId;

  long activity(String status) {
    if (hobbyId == null) {
      hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?, 'Report test') RETURNING hobby_id",
              Long.class,
              "report-" + UUID.randomUUID());
      stageId =
          jdbc.queryForObject(
              "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'Seed') RETURNING hobby_stage_id",
              Long.class,
              hobbyId);
    }
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,'Report test',?) RETURNING activity_id",
        Long.class,
        stageId,
        "report-" + UUID.randomUUID(),
        status);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    if (stageId != null) {
      jdbc.update("DELETE FROM activities WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobby_stages WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobbyId);
    }
    super.cleanupSeededHobbies(userId);
  }

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> report(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reports"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String body(String targetType, long targetId, String reason, String description) {
    return """
        {"targetType":"%s","targetId":%d,"reason":"%s","description":"%s"}
        """
        .formatted(targetType, targetId, reason, description);
  }

  @Test
  void reportsPublishedActivityAndReturnsReceiptFields() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var response =
        report(token, body("ACTIVITY", id, "WRONG_INFORMATION", "준비물 수량 설명이 단계와 서로 달라요."));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    var envelope = mapper.readTree(response.body());
    // Lock the exact wire contract a frontend client codes against: no missing/extra fields,
    // and the Korean message text travels intact (not mangled) over the wire.
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("isSuccess").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("REPORT201_1");
    assertThat(envelope.path("message").asText()).isEqualTo("신고가 접수되었습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder("reportId", "targetType", "targetId", "status", "createdAt");
    assertThat(result.path("targetType").asText()).isEqualTo("ACTIVITY");
    assertThat(result.path("targetId").asLong()).isEqualTo(id);
    assertThat(result.path("status").asText()).isEqualTo("RECEIVED");
    assertThat(java.time.Instant.parse(result.path("createdAt").asText())).isNotNull();
    assertThat(result.path("reportId").asLong()).isPositive();
  }

  @Test
  void rejectsDuplicateReportWithin24HoursButAllowsDifferentReason() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    assertThat(
            report(token, body("ACTIVITY", id, "WRONG_INFORMATION", "준비물 수량 설명이 단계와 서로 달라요."))
                .statusCode())
        .isEqualTo(201);
    var duplicate = report(token, body("ACTIVITY", id, "WRONG_INFORMATION", "똑같은 신고를 또 접수합니다."));
    assertThat(duplicate.statusCode()).isEqualTo(409);
    var errorEnvelope = mapper.readTree(duplicate.body());
    assertThat(fieldNames(errorEnvelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(errorEnvelope.path("isSuccess").asBoolean()).isFalse();
    assertThat(errorEnvelope.path("code").asText()).isEqualTo("REPORT409_1");
    assertThat(errorEnvelope.path("message").asText()).isEqualTo("같은 대상·사유에 대한 24시간 내 중복 신고");
    assertThat(errorEnvelope.path("result").isNull()).isTrue();
    var differentReason = report(token, body("ACTIVITY", id, "BROKEN_LINK", "이번엔 다른 사유로 신고합니다."));
    assertThat(differentReason.statusCode()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isEqualTo(2);
  }

  List<String> fieldNames(tools.jackson.databind.JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void rejectsUnsupportedTargetTypeOrReason() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var badType = report(token, body("USER", id, "WRONG_INFORMATION", "지원하지 않는 대상 타입입니다."));
    assertThat(badType.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(badType.body()).path("code").asText()).isEqualTo("REPORT400_1");
    var badReason = report(token, body("ACTIVITY", id, "SPAM", "지원하지 않는 사유입니다."));
    assertThat(badReason.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(badReason.body()).path("code").asText()).isEqualTo("REPORT400_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void rejectsDescriptionShorterThanTenOrLongerThanThousandCharsAfterTrimming() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var tooShort = report(token, body("ACTIVITY", id, "OTHER", "   짤막함   "));
    assertThat(tooShort.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(tooShort.body()).path("code").asText()).isEqualTo("REPORT400_1");
    var tooLong = report(token, body("ACTIVITY", id, "OTHER", "가".repeat(1001)));
    assertThat(tooLong.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(tooLong.body()).path("code").asText()).isEqualTo("REPORT400_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void rejectsMissingOrUnpublishedActivityAndAlwaysRejectsSponsoredContent() throws Exception {
    String token = activeToken();
    for (String state : List.of("DRAFT", "REVIEW", "REJECTED")) {
      var response = report(token, body("ACTIVITY", activity(state), "OTHER", "아직 공개되지 않은 활동입니다."));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("REPORT404_1");
    }
    assertThat(
            report(token, body("ACTIVITY", 9223372036854775807L, "OTHER", "존재하지 않는 활동입니다."))
                .statusCode())
        .isEqualTo(404);
    // SPONSORED_CONTENT can never exist yet: API-49 hasn't shipped the table it would live in.
    var sponsored = report(token, body("SPONSORED_CONTENT", 1L, "OTHER", "아직 구현되지 않은 제휴 콘텐츠입니다."));
    assertThat(sponsored.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(sponsored.body()).path("code").asText()).isEqualTo("REPORT404_1");
  }

  @Test
  void requiresAuthenticationAndRejectsSuspendedOnboardingAndWithdrawnAccounts() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    String token = signedIn.path("accessToken").asText();
    long id = activity("PUBLISHED");

    assertThat(report(null, body("ACTIVITY", id, "OTHER", "토큰 없이 신고를 시도합니다.")).statusCode())
        .isEqualTo(401);
    assertThat(report(token, body("ACTIVITY", id, "OTHER", "온보딩 미완료 상태로 신고합니다.")).statusCode())
        .isEqualTo(403);

    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", userId);
    assertThat(report(token, body("ACTIVITY", id, "OTHER", "정지된 계정으로 신고합니다.")).statusCode())
        .isEqualTo(403);

    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN', withdrawn_at=now(),"
            + " restorable_until=now()+interval '30 days', status_before_withdrawal='ACTIVE'"
            + " WHERE user_id=?",
        userId);
    assertThat(report(token, body("ACTIVITY", id, "OTHER", "탈퇴한 계정으로 신고합니다.")).statusCode())
        .isEqualTo(403);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void rejectsMissingRequiredFieldsAsCommonBadRequest() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var missingTargetId =
        report(
            token,
            "{\"targetType\":\"ACTIVITY\",\"reason\":\"OTHER\",\"description\":\"필수값이 빠진 요청입니다.\"}");
    assertThat(missingTargetId.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(missingTargetId.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    var blankDescription = report(token, body("ACTIVITY", id, "OTHER", ""));
    assertThat(blankDescription.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(blankDescription.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsFloatAndStringTargetIdInsteadOfTruncatingOrCoercing() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    // Jackson's default ACCEPT_FLOAT_AS_INT would truncate 1.5 to 1 and silently report the
    // wrong, truncated activity instead of rejecting the malformed request.
    var floatTargetId =
        report(
            token,
            "{\"targetType\":\"ACTIVITY\",\"targetId\":%d.5,\"reason\":\"OTHER\",\"description\":\"소수 targetId 테스트입니다.\"}"
                .formatted(id));
    assertThat(floatTargetId.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(floatTargetId.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    var stringTargetId =
        report(
            token,
            "{\"targetType\":\"ACTIVITY\",\"targetId\":\"%d\",\"reason\":\"OTHER\",\"description\":\"문자열 targetId 테스트입니다.\"}"
                .formatted(id));
    assertThat(stringTargetId.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(stringTargetId.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void rejectsNonStringTargetTypeReasonOrDescription() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var numericTargetType =
        report(
            token,
            "{\"targetType\":1,\"targetId\":%d,\"reason\":\"OTHER\",\"description\":\"숫자 targetType 테스트입니다.\"}"
                .formatted(id));
    assertThat(numericTargetType.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(numericTargetType.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    var booleanReason =
        report(
            token,
            "{\"targetType\":\"ACTIVITY\",\"targetId\":%d,\"reason\":true,\"description\":\"불린 reason 테스트입니다.\"}"
                .formatted(id));
    assertThat(booleanReason.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(booleanReason.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    var numericDescription =
        report(
            token,
            "{\"targetType\":\"ACTIVITY\",\"targetId\":%d,\"reason\":\"OTHER\",\"description\":12345678901}"
                .formatted(id));
    assertThat(numericDescription.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(numericDescription.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void rejectsDescriptionBelowTenCodePointsEvenWhenMultiByteEmojiPadUtf16Length() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    // "😀" is a surrogate pair: String.length() counts it as 2, but Postgres length() and
    // codePointCount() both count it as 1. Five of them is UTF-16 length 10 (would wrongly
    // pass a length()-based check) but only 5 code points (correctly rejected here, before
    // ever reaching the DB's own length(btrim(...)) >= 10 constraint).
    var emojiDescription = report(token, body("ACTIVITY", id, "OTHER", "😀😀😀😀😀"));
    assertThat(emojiDescription.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(emojiDescription.body()).path("code").asText())
        .isEqualTo("REPORT400_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void concurrentIdenticalReportsFromSameUserInsertExactlyOnce() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    String payload = body("ACTIVITY", id, "OTHER", "동시 요청 중복 방지를 확인하는 테스트입니다.");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return report(token, payload);
                }));
      start.countDown();
      int created = 0;
      int duplicate = 0;
      for (var f : futures) {
        var response = f.get(15, TimeUnit.SECONDS);
        if (response.statusCode() == 201) created++;
        else if (response.statusCode() == 409) duplicate++;
        else fail("unexpected status " + response.statusCode());
      }
      assertThat(created).isEqualTo(1);
      assertThat(duplicate).isEqualTo(3);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id=?", Integer.class, id))
        .isEqualTo(1);
  }
}
