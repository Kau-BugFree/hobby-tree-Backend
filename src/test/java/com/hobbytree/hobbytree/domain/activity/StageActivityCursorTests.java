package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.service.StageActivityCursor;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StageActivityCursorTests {
  private final StageActivityCursor cursors =
      new StageActivityCursor(new SecretKeySpec(new byte[64], "HmacSHA256"));

  @Test
  void acceptsOnlyTheServerIssuedCursorForItsUserAndStage() {
    String cursor = cursors.encode(11, 22, 33);
    assertThat(cursors.decode(null, 11, 22)).isZero();
    assertThat(cursors.decode(cursor, 11, 22)).isEqualTo(33);
    rejects(cursor, 12, 22);
    rejects(cursor, 11, 23);
    byte[] otherKey = new byte[64];
    otherKey[0] = 1;
    String foreign =
        new StageActivityCursor(new SecretKeySpec(otherKey, "HmacSHA256")).encode(11, 22, 33);
    rejects(foreign, 11, 22);
  }

  @Test
  void rejectsEditedPayloadsSignaturesAndNoncanonicalEncoding() {
    String cursor = cursors.encode(11, 22, 33);
    String[] parts = cursor.split("\\.");
    String altered =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("api24-id-v1:11:22:34".getBytes(StandardCharsets.UTF_8));
    rejects(altered + "." + parts[1], 11, 22);
    byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
    signature[0] ^= 1;
    rejects(
        parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature), 11, 22);
    rejects(cursor + "=", 11, 22);
    rejects(cursors.encode(11, 22, 0), 11, 22);
    rejects(cursors.encode(11, 22, -1), 11, 22);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "invalid", "e30.signature", "a.b.c", "%%%"})
  void rejectsMalformedCursors(String value) {
    rejects(value, 11, 22);
  }

  @Test
  void rejectsOversizedCursorBeforeDecoding() {
    rejects("a".repeat(257), 11, 22);
  }

  private void rejects(String token, long user, long stage) {
    assertThatThrownBy(() -> cursors.decode(token, user, stage))
        .isInstanceOfSatisfying(
            ProjectException.class,
            error ->
                assertThat(error.getErrorCode()).isEqualTo(GeneralErrorCode.COMMON_BAD_REQUEST));
  }
}
