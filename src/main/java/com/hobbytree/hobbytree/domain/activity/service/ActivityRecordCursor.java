package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** API-30 keyset positions are independent of the continued existence of the anchor row. */
@Component
public class ActivityRecordCursor {
  private static final String VERSION = "api30-started-v1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final SecretKeySpec key;

  public ActivityRecordCursor(SecretKeySpec serviceSigningKey) {
    key = new SecretKeySpec(mac(serviceSigningKey, "hobbytree/api-30/cursor-key/v1"), "HmacSHA256");
  }

  public String encode(long userId, long treeId, String status, Position position) {
    String payload =
        VERSION
            + "|"
            + userId
            + "|"
            + treeId
            + "|"
            + (status == null ? "ALL" : status)
            + "|"
            + (position.startedAt() == null ? "~" : position.startedAt())
            + "|"
            + position.recordId();
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + ENCODER.encodeToString(mac(key, payload));
  }

  public Position decode(String token, long userId, long treeId, String status) {
    if (token == null) return null;
    try {
      if (token.length() > 512) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      byte[] bytes = DECODER.decode(parts[0]);
      byte[] signature = DECODER.decode(parts[1]);
      if (!ENCODER.encodeToString(bytes).equals(parts[0])
          || !ENCODER.encodeToString(signature).equals(parts[1]))
        throw new IllegalArgumentException();
      String payload = new String(bytes, StandardCharsets.UTF_8);
      if (!MessageDigest.isEqual(mac(key, payload), signature))
        throw new IllegalArgumentException();
      String[] values = payload.split("\\|", -1);
      if (values.length != 6
          || !VERSION.equals(values[0])
          || Long.parseLong(values[1]) != userId
          || Long.parseLong(values[2]) != treeId
          || !(status == null ? "ALL" : status).equals(values[3]))
        throw new IllegalArgumentException();
      Instant startedAt = "~".equals(values[4]) ? null : Instant.parse(values[4]);
      var position = new Position(startedAt, Long.parseLong(values[5]));
      if (position.recordId() < 1
          || (startedAt != null && startedAt.getNano() % 1000 != 0)
          || !encode(userId, treeId, status, position).equals(token))
        throw new IllegalArgumentException();
      return position;
    } catch (IllegalArgumentException | DateTimeException exception) {
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

  public record Position(Instant startedAt, long recordId) {}
}
