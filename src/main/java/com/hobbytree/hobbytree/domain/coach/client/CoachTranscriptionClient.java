package com.hobbytree.hobbytree.domain.coach.client;

import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.config.CoachAiProperties;
import com.hobbytree.hobbytree.domain.coach.config.CoachSttProperties;
import com.hobbytree.hobbytree.domain.coach.dto.CoachTranscriptionResponse;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CoachTranscriptionClient {
  private final CoachAiProperties ai;
  private final CoachSttProperties stt;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public CoachTranscriptionClient(
      CoachAiProperties ai, CoachSttProperties stt, ObjectMapper mapper) {
    if (stt.enabled() && !ai.enabled())
      throw new IllegalArgumentException("Enabled STT requires configured coach AI");
    this.ai = ai;
    this.stt = stt;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(ai.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public void requireEnabled() {
    if (!stt.enabled() || !ai.enabled()) throw new ProjectException(CoachErrorCode.STT_UNAVAILABLE);
  }

  public CoachTranscriptionResponse transcribe(byte[] audio, String contentType) {
    requireEnabled();
    var request =
        HttpRequest.newBuilder(ai.baseUrl().resolve("/backend/coach/transcribe"))
            .timeout(stt.requestTimeout())
            .header("Content-Type", contentType)
            .header("X-Coach-Service-Token", ai.serviceToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
            .build();
    var pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
    try {
      var response = pending.get(stt.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
      var error =
          switch (response.statusCode()) {
            case 200 -> null;
            case 400 -> CoachErrorCode.STT_INVALID_AUDIO;
            case 413 -> CoachErrorCode.STT_TOO_LARGE;
            case 415 -> CoachErrorCode.STT_UNSUPPORTED_MEDIA;
            case 422 -> CoachErrorCode.STT_NO_SPEECH;
            case 401, 403, 429, 503 -> CoachErrorCode.STT_UNAVAILABLE;
            case 504 -> CoachErrorCode.STT_TIMEOUT;
            default -> CoachErrorCode.STT_BAD_RESPONSE;
          };
      if (error != null) throw new ProjectException(error);
      try {
        var body = mapper.readTree(response.body());
        if (body == null
            || !body.isObject()
            || !body.path("text").isString()
            || body.path("text").asText().isBlank()) throw new IllegalArgumentException();
        // Keep the complete transcript for editing; the chat endpoint has its own 2,000-char limit.
        return new CoachTranscriptionResponse(body.path("text").asText().strip());
      } catch (RuntimeException invalid) {
        throw new ProjectException(CoachErrorCode.STT_BAD_RESPONSE);
      }
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new ProjectException(CoachErrorCode.STT_TIMEOUT);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ProjectException(CoachErrorCode.STT_UNAVAILABLE);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof HttpConnectTimeoutException)
        throw new ProjectException(CoachErrorCode.STT_UNAVAILABLE);
      if (e.getCause() instanceof HttpTimeoutException)
        throw new ProjectException(CoachErrorCode.STT_TIMEOUT);
      if (e.getCause() instanceof ProjectException failure) throw failure;
      throw new ProjectException(CoachErrorCode.STT_UNAVAILABLE);
    }
  }

  // Bound success AND error bodies. Never expose/log upstream bodies, audio or service tokens.
  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long remaining = 262144;

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) remaining -= buffer.remaining();
      if (remaining < 0) {
        subscription.cancel();
        delegate.onError(new ProjectException(CoachErrorCode.STT_BAD_RESPONSE));
      } else delegate.onNext(buffers);
    }

    public void onError(Throwable failure) {
      delegate.onError(failure);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }
}
