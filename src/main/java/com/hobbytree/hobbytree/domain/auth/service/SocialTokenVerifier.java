package com.hobbytree.hobbytree.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.config.AuthProperties;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import java.net.SocketTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class SocialTokenVerifier {
  public record KakaoTokenInfo(
      @JsonProperty("app_id") Long appId, Long id, @JsonProperty("expires_in") Long expiresIn) {}

  public record KakaoUser(Long id) {}

  private final RestClient kakao;
  private final JwtDecoder google;
  private final AuthProperties properties;

  public SocialTokenVerifier(
      @Qualifier("kakaoRestClient") RestClient kakao,
      @Qualifier("googleJwtDecoder") JwtDecoder google,
      AuthProperties properties) {
    this.kakao = kakao;
    this.google = google;
    this.properties = properties;
  }

  public String verify(AuthProvider provider, String token) {
    try {
      if (provider == AuthProvider.GOOGLE) return google.decode(token).getSubject();
      KakaoTokenInfo info =
          kakao
              .get()
              .uri("/v1/user/access_token_info")
              .headers(h -> h.setBearerAuth(token))
              .retrieve()
              .body(KakaoTokenInfo.class);
      if (info == null
          || info.appId() == null
          || info.appId() != properties.kakaoAppId()
          || info.id() == null
          || info.id() <= 0
          || info.expiresIn() == null
          || info.expiresIn() <= 0) throw new AuthException(AuthErrorCode.SOCIAL_TOKEN);
      KakaoUser user =
          kakao
              .get()
              .uri("/v2/user/me")
              .headers(h -> h.setBearerAuth(token))
              .retrieve()
              .body(KakaoUser.class);
      if (user == null || !info.id().equals(user.id()))
        throw new AuthException(AuthErrorCode.PROVIDER_ERROR);
      return user.id().toString();
    } catch (RestClientResponseException e) {
      // Kakao may report platform failures with HTTP 400; do not classify all 4xx as invalid
      // tokens.
      if (e.getStatusCode().value() == 401) throw new AuthException(AuthErrorCode.SOCIAL_TOKEN);
      throw new AuthException(AuthErrorCode.PROVIDER_ERROR);
    } catch (ResourceAccessException e) {
      throw providerFailure(e);
    } catch (JwtException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof RestClientException) throw providerFailure(cause);
      throw new AuthException(AuthErrorCode.SOCIAL_TOKEN);
    } catch (RestClientException e) {
      throw new AuthException(AuthErrorCode.PROVIDER_ERROR);
    }
  }

  private AuthException providerFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SocketTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException)
        return new AuthException(AuthErrorCode.PROVIDER_TIMEOUT);
    return new AuthException(AuthErrorCode.PROVIDER_ERROR);
  }
}
