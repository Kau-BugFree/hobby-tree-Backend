package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.coach.client.CoachTranscriptionClient;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachTranscriptionResponse;
import com.hobbytree.hobbytree.domain.coach.service.CoachTranscriptionService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class CoachTranscriptionIntegrationTests extends IntegrationTestSupport {
  @MockitoBean CoachTranscriptionClient ai;
  static final String PATH = "/api/v1/coach/transcribe";
  String token;
  long userId;

  @BeforeEach
  void prepareStt() throws Exception {
    var signedIn = result(login());
    userId = signedIn.path("userId").asLong();
    token = signedIn.path("accessToken").asText();
    createdUsers.add(userId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", userId);
    when(ai.transcribe(any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new CoachTranscriptionResponse("간장 두 큰술");
            });
  }

  @AfterEach
  void clearSttBudget() {
    redis.delete("auth:ratelimit:coach-stt:" + userId);
  }

  HttpResponse<String> upload(String bearer, String field, String type, byte[] audio)
      throws Exception {
    String boundary = "hobbytree-isolated-stt-boundary";
    var body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + field
                + "\"; filename=\"voice.m4a\"\r\n"
                + (type == null ? "" : "Content-Type: " + type + "\r\n")
                + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(audio);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return send(bearer, "multipart/form-data; boundary=" + boundary, body.toByteArray());
  }

  HttpResponse<String> send(String bearer, String type, byte[] body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  void expect(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo(code);
  }

  @Test
  void realMultipartReturnsTextWithoutConversationOrMessageWrites() throws Exception {
    long conversations =
        jdbc.queryForObject("SELECT count(*) FROM coach_conversations", Long.class);
    long messages = jdbc.queryForObject("SELECT count(*) FROM coach_messages", Long.class);
    var response = upload(token, "file", "audio/mp4", new byte[] {1, 2, 3});
    expect(response, 200, "COACH200_4");
    assertThat(result(response).propertyNames()).containsExactly("text");
    assertThat(result(response).path("text").asText()).isEqualTo("간장 두 큰술");
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    verify(ai).transcribe(new byte[] {1, 2, 3}, "audio/mp4");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM coach_conversations", Long.class))
        .isEqualTo(conversations);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM coach_messages", Long.class))
        .isEqualTo(messages);
  }

  @Test
  void rejectsAnonymousAndPendingMembersBeforeAi() throws Exception {
    assertThat(upload(null, "file", "audio/mp4", new byte[] {1}).statusCode()).isEqualTo(401);
    jdbc.update("UPDATE users SET account_status='PENDING_ONBOARDING' WHERE user_id=?", userId);
    assertThat(upload(token, "file", "audio/mp4", new byte[] {1}).statusCode()).isEqualTo(403);
    verifyNoInteractions(ai);
  }

  @Test
  void enforcesActualServletFileLimitAtSevenMiB() throws Exception {
    expect(
        upload(token, "file", "audio/mp4", new byte[CoachTranscriptionService.MAX_AUDIO_BYTES]),
        200,
        "COACH200_4");
    expect(
        upload(token, "file", "audio/mp4", new byte[CoachTranscriptionService.MAX_AUDIO_BYTES + 1]),
        413,
        "COACH413_1");
    verify(ai, times(1)).transcribe(any(), any());
  }

  @Test
  void emptyMissingAndNonAudioPartsDoNotInvokeAi() throws Exception {
    expect(upload(token, "file", "audio/mp4", new byte[0]), 400, "COACH400_3");
    expect(upload(token, "wrong", "audio/mp4", new byte[] {1}), 400, "COMMON400_1");
    expect(upload(token, "file", "application/json", new byte[] {1}), 415, "COACH415_1");
    verify(ai, never()).transcribe(any(), any());
  }

  @Test
  void rejectsMalformedMultipartAndJsonWithoutInternalError() throws Exception {
    expect(send(token, "multipart/form-data", new byte[] {1}), 400, "COMMON400_1");
    expect(
        send(token, "application/json", "{}".getBytes(StandardCharsets.UTF_8)), 415, "COMMON415_1");
    verify(ai, never()).transcribe(any(), any());
  }

  @Test
  void redisBudgetReturnsRetryAfterAndDoesNotConsumeChatBudget() throws Exception {
    for (int i = 0; i < 3; i++)
      expect(upload(token, "file", "audio/mp4", new byte[] {1}), 200, "COACH200_4");
    var blocked = upload(token, "file", "audio/mp4", new byte[] {1});
    expect(blocked, 429, "COMMON429_1");
    assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").orElse("0")))
        .isBetween(1L, 60L);
    assertThat(redis.hasKey("auth:ratelimit:coach-chat:" + userId)).isFalse();
    verify(ai, times(3)).transcribe(any(), any());
  }

  @Test
  void rejectsDuplicateFilePartsBeforeAnyUpstreamCall() throws Exception {
    String part =
        "--duplicate\r\nContent-Disposition: form-data; name=\"file\"; filename=\"v.m4a\"\r\nContent-Type: audio/mp4\r\n\r\nx\r\n";
    byte[] body = (part + part + "--duplicate--\r\n").getBytes(StandardCharsets.UTF_8);
    expect(send(token, "multipart/form-data; boundary=duplicate", body), 400, "COMMON400_1");
    verifyNoInteractions(ai);
  }

  @Test
  void preservesSttErrorsIncludingNonIdempotentTimeoutAndUnavailableFeature() throws Exception {
    for (var code :
        List.of(
            CoachErrorCode.STT_NO_SPEECH,
            CoachErrorCode.STT_TIMEOUT,
            CoachErrorCode.STT_UNAVAILABLE)) {
      doThrow(new ProjectException(code)).when(ai).transcribe(any(), any());
      expect(
          upload(token, "file", "audio/mp4", new byte[] {1}),
          code.getStatus().value(),
          code.getCode());
    }
  }
}
