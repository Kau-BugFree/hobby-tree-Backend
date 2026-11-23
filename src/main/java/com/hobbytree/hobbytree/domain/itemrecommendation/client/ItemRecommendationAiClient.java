package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
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
public class ItemRecommendationAiClient {
  private final ItemRecommendationAiProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  /** Creates a reusable client with bounded connection time and no redirect forwarding. */
  public ItemRecommendationAiClient(
      ItemRecommendationAiProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** Looks up the cached catalog for one activity's materials; no automatic retry on failure. */
  public ItemRecommendationAiResponse.Result recommend(ItemRecommendationAiRequest body) {
    if (!properties.enabled())
      throw new ProjectException(ItemRecommendationErrorCode.AI_UNAVAILABLE);
    var request =
        HttpRequest.newBuilder(properties.baseUrl().resolve("/internal/v1/item-recommendations"))
            .timeout(properties.requestTimeout())
            .header("Content-Type", "application/json")
            .header("X-Service-Token", properties.serviceToken())
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    var pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
    try {
      var response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 503)
        throw new ProjectException(ItemRecommendationErrorCode.AI_UNAVAILABLE);
      // 401/413/422 are BE<->AI contract/config problems, not a user auth failure, so they fall
      // through to the same opaque AI_BAD_RESPONSE as any other unexpected status.
      if (response.statusCode() != 200)
        throw new ProjectException(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
      try {
        var result = mapper.readValue(response.body(), ItemRecommendationAiResponse.class);
        ItemRecommendationAiResponseValidator.validate(body, result);
        return result.result();
      } catch (RuntimeException invalid) {
        throw new ProjectException(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
      }
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new ProjectException(ItemRecommendationErrorCode.AI_TIMEOUT);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ProjectException(ItemRecommendationErrorCode.AI_UNAVAILABLE);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof java.net.http.HttpConnectTimeoutException)
        throw new ProjectException(ItemRecommendationErrorCode.AI_UNAVAILABLE);
      if (e.getCause() instanceof java.net.http.HttpTimeoutException)
        throw new ProjectException(ItemRecommendationErrorCode.AI_TIMEOUT);
      if (e.getCause() instanceof ProjectException failure) throw failure;
      throw new ProjectException(ItemRecommendationErrorCode.AI_UNAVAILABLE);
    }
  }

  // Bound the body while receiving it, including malformed/error responses. Never log it or
  // the upstream exception: either can contain tokens, purchase-link query params or diagnostics.
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
        delegate.onError(new ProjectException(ItemRecommendationErrorCode.AI_BAD_RESPONSE));
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
