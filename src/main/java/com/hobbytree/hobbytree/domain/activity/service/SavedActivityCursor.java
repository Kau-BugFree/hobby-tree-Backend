package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SavedActivityCursor {
  private static final String VERSION = "api33-saved-v1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final SecretKeySpec key;

  public SavedActivityCursor(SecretKeySpec serviceSigningKey) {
    key = new SecretKeySpec(mac(serviceSigningKey, "hobbytree/api-33/cursor-key/v1"), "HmacSHA256");
  }

  /** Signs the page boundary together with the member and the selected hobby. */
  public String encode(long userId, long hobbyId, Position position) {
    String payload =
        VERSION
            + "|"
            + userId
            + "|"
            + hobbyId
            + "|"
            + position.activityId()
            + "|"
            + position.savedAt();
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(key, payload));
  }

  /** Verifies the cursor's integrity and scope. Hobby ID zero represents the unfiltered list. */
  public Position decode(String token, long userId, long hobbyId) {
    if (token == null) return null;
    try {
      if (token.length() > 256) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      byte[] bytes = DECODER.decode(parts[0]);
      byte[] signature = DECODER.decode(parts[1]);
      String payload = new String(bytes, StandardCharsets.UTF_8);
      if (!ENCODER.encodeToString(bytes).equals(parts[0])
          || !ENCODER.encodeToString(signature).equals(parts[1])
          || !MessageDigest.isEqual(mac(key, payload), signature))
        throw new IllegalArgumentException();
      String[] values = payload.split("\\|", -1);
      if (values.length != 5
          || !VERSION.equals(values[0])
          || Long.parseLong(values[1]) != userId
          || Long.parseLong(values[2]) != hobbyId) throw new IllegalArgumentException();
      var position = new Position(Instant.parse(values[4]), Long.parseLong(values[3]));
      if (position.activityId() < 1) throw new IllegalArgumentException();
      return position;
    } catch (IllegalArgumentException | DateTimeParseException exception) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  /** Computes an HMAC with a fresh instance because cursors are handled concurrently. */
  private static byte[] mac(SecretKeySpec key, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cursor signing is unavailable", exception);
    }
  }

  public record Position(Instant savedAt, long activityId) {}
}
