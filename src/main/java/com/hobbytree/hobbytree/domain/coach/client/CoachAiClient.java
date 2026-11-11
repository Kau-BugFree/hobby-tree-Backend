package com.hobbytree.hobbytree.domain.coach.client;

import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.config.CoachAiProperties;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CoachAiClient {
  private final CoachAiProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public CoachAiClient(CoachAiProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public CoachAiResponse chat(CoachAiRequest body) {
    if (!properties.enabled()) throw new ProjectException(CoachErrorCode.AI_UNAVAILABLE);
    var request =
        HttpRequest.newBuilder(properties.baseUrl().resolve("/backend/coach/chat"))
            .timeout(properties.requestTimeout())
            .header("Content-Type", "application/json")
            .header("X-Coach-Service-Token", properties.serviceToken())
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    var pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
    try {
      // Includes body consumption, unlike a header-only timeout. No automatic retry: the caller
      // must reuse its durable requestId when an upstream response may already have been committed.
      var response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 429 || response.statusCode() == 503)
        throw new ProjectException(CoachErrorCode.AI_UNAVAILABLE);
      if (response.statusCode() != 200) throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);
      try {
        var result = mapper.readValue(response.body(), CoachAiResponse.class);
        CoachAiResponseValidator.validate(body, result);
        return result;
      } catch (RuntimeException invalid) {
        throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);
      }
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new ProjectException(CoachErrorCode.AI_TIMEOUT);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ProjectException(CoachErrorCode.AI_UNAVAILABLE);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof java.net.http.HttpTimeoutException)
        throw new ProjectException(CoachErrorCode.AI_TIMEOUT);
      if (e.getCause() instanceof ProjectException failure) throw failure;
      throw new ProjectException(CoachErrorCode.AI_UNAVAILABLE);
    }
  }

  // Bound the body while receiving it, including malformed/error responses. Never log it or
  // the upstream exception: either can contain prompts, authentication headers or private data.
  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long remaining = 262144;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) remaining -= buffer.remaining();
      if (remaining < 0) {
        subscription.cancel();
        delegate.onError(new ProjectException(CoachErrorCode.AI_BAD_RESPONSE));
      } else delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
      delegate.onError(failure);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }
}
