package com.hobbytree.hobbytree.domain.recommendation.client;

import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RecommendationAiClient {
  private final RecommendationAiProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  /** Creates a reusable client with bounded connection time and no redirect forwarding. */
  public RecommendationAiClient(RecommendationAiProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** Executes the stateless rules engine once, without forwarding user authentication. */
  public JsonNode recommend(JsonNode body) {
    if (!properties.enabled()) throw new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE);
    var request =
        HttpRequest.newBuilder(properties.baseUrl().resolve("/api/v1/recommendations"))
            .timeout(properties.requestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    var pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
    try {
      // Includes body consumption, unlike a header-only timeout. The public endpoint controls
      // retry.
      var response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 429 || response.statusCode() == 503)
        throw new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE);
      if (response.statusCode() != 200)
        throw new ProjectException(RecommendationErrorCode.AI_BAD_RESPONSE);
      try {
        return mapper.readTree(response.body());
      } catch (RuntimeException invalid) {
        throw new ProjectException(RecommendationErrorCode.AI_BAD_RESPONSE);
      }
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new ProjectException(RecommendationErrorCode.AI_TIMEOUT);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof java.net.http.HttpConnectTimeoutException)
        throw new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE);
      if (e.getCause() instanceof java.net.http.HttpTimeoutException)
        throw new ProjectException(RecommendationErrorCode.AI_TIMEOUT);
      if (e.getCause() instanceof ProjectException failure) throw failure;
      throw new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE);
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

    /** Cancels reception before accumulating a response larger than the configured hard limit. */
    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) remaining -= buffer.remaining();
      if (remaining < 0) {
        subscription.cancel();
        delegate.onError(new ProjectException(RecommendationErrorCode.AI_BAD_RESPONSE));
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
