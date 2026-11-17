package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Integrity-protected cursors for API-24's temporary activity-ID ordering only. */
@Component
public class StageActivityCursor {
  private static final String VERSION = "api24-id-v1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final SecretKeySpec key;

  public StageActivityCursor(SecretKeySpec serviceSigningKey) {
    // Derive a domain-specific key; cursor signing must not become a JWT signing oracle.
    key = new SecretKeySpec(mac(serviceSigningKey, "hobbytree/api-24/cursor-key/v1"), "HmacSHA256");
  }

  public String encode(long userId, long stageId, long activityId) {
    String payload = VERSION + ":" + userId + ":" + stageId + ":" + activityId;
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + ENCODER.encodeToString(mac(key, payload));
  }

  /** Rejects modified, noncanonical, cross-stage and cross-user cursors before querying a page. */
  public long decode(String token, long userId, long stageId) {
    if (token == null) return 0;
    try {
      if (token.length() > 256) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      byte[] payloadBytes = DECODER.decode(parts[0]);
      byte[] signature = DECODER.decode(parts[1]);
      if (!ENCODER.encodeToString(payloadBytes).equals(parts[0])
          || !ENCODER.encodeToString(signature).equals(parts[1]))
        throw new IllegalArgumentException();
      String payload = new String(payloadBytes, StandardCharsets.UTF_8);
      if (!MessageDigest.isEqual(mac(key, payload), signature))
        throw new IllegalArgumentException();
      String[] values = payload.split(":", -1);
      if (values.length != 4
          || !VERSION.equals(values[0])
          || Long.parseLong(values[1]) != userId
          || Long.parseLong(values[2]) != stageId) throw new IllegalArgumentException();
      long afterId = Long.parseLong(values[3]);
      if (afterId < 1 || !encode(userId, stageId, afterId).equals(token))
        throw new IllegalArgumentException();
      return afterId;
    } catch (IllegalArgumentException exception) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private static byte[] mac(SecretKeySpec key, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cursor signing is unavailable", exception);
    }
  }
}
