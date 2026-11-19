package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.service.StageActivityCursor;
import com.hobbytree.hobbytree.domain.activity.service.StageActivityCursor.Position;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StageActivityCursorTests {
  private final SecretKeySpec signing = new SecretKeySpec(new byte[64], "HmacSHA256");
  private final StageActivityCursor cursors = new StageActivityCursor(signing);

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
  void roundTripsAssignedAndUnassignedPositionsWithoutConfusingTheFirstPage(Integer order) {
    String token = cursors.encode(11, 22, order, Long.MAX_VALUE);
    assertThat(cursors.decode(null, 11, 22)).isNull();
    assertThat(cursors.decode(token, 11, 22)).isEqualTo(new Position(order, Long.MAX_VALUE));
    rejects(token, 12, 22);
    rejects(token, 11, 23);
    byte[] other = new byte[64];
    other[0] = 1;
    rejects(
        new StageActivityCursor(new SecretKeySpec(other, "HmacSHA256")).encode(11, 22, order, 33),
        11,
        22);
  }

  @Test
  void rejectsEditedPayloadsSignaturesAndPaddedEncoding() {
    String token = cursors.encode(11, 22, 3, 33);
    String[] parts = token.split("\\.");
    String altered =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("api24-order-v2:11:22:4:33".getBytes(StandardCharsets.UTF_8));
    rejects(altered + "." + parts[1], 11, 22);
    byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
    signature[0] ^= 1;
    rejects(
        parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature), 11, 22);
    rejects(token + "=", 11, 22);
  }

  @Test
  void retiresCorrectlySignedLegacyCursorsInsteadOfReinterpretingTheirId() throws Exception {
    rejects(signed("api24-id-v1:11:22:33", "hobbytree/api-24/cursor-key/v1"), 11, 22);
    rejects(signed("api24-id-v1:11:22:33", "hobbytree/api-24/cursor-key/order-v2"), 11, 22);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "api24-order-v2:011:22:1:33",
        "api24-order-v2:11:+22:1:33",
        "api24-order-v2:11:22:01:33",
        "api24-order-v2:11:22:-1:33",
        "api24-order-v2:11:22:2147483648:33",
        "api24-order-v2:11:22::33",
        "api24-order-v2:11:22:NULL:33",
        "api24-order-v2:11:22:null:0",
        "api24-order-v2:11:22:null:-1",
        "api24-order-v2:11:22:null:033",
        "api24-order-v2:11:22:null:9223372036854775808",
        "api24-order-v2:11:22:null:33:extra",
        "another-api:11:22:null:33"
      })
  void rejectsNoncanonicalAndOutOfRangeFieldsEvenWithAValidSignature(String payload)
      throws Exception {
    rejects(signed(payload, "hobbytree/api-24/cursor-key/order-v2"), 11, 22);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "invalid", "e30.signature", "a.b.c", "%%%"})
  void rejectsMalformedCursors(String value) {
    rejects(value, 11, 22);
  }

  @Test
  void rejectsOversizedCursorBeforeDecodingAndInvalidServerPositions() {
    rejects("a".repeat(257), 11, 22);
    assertThatThrownBy(() -> cursors.encode(11, 22, -1, 33))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cursors.encode(11, 22, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String signed(String payload, String domain) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(signing);
    var key = new SecretKeySpec(mac.doFinal(domain.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    mac.init(key);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private void rejects(String token, long user, long stage) {
    assertThatThrownBy(() -> cursors.decode(token, user, stage))
        .isInstanceOfSatisfying(
            ProjectException.class,
            error ->
                assertThat(error.getErrorCode()).isEqualTo(GeneralErrorCode.COMMON_BAD_REQUEST));
  }
}
