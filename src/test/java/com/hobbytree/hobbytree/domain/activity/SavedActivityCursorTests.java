package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCursor;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityCursor;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityCursor.Position;
import com.hobbytree.hobbytree.domain.activity.service.StageActivityCursor;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SavedActivityCursorTests {
  private final SecretKeySpec signing = new SecretKeySpec(new byte[64], "HmacSHA256");
  private final SavedActivityCursor cursors = new SavedActivityCursor(signing);

  @Test
  void microsecondPositionRoundTripsAndScopeCannotChange() {
    var position = new Position(Instant.parse("2026-11-19T00:00:00.123456Z"), 3);
    String token = cursors.encode(1, 2, position);
    assertThat(cursors.decode(token, 1, 2)).isEqualTo(position);
    assertThat(cursors.decode(null, 1, 2)).isNull();
    assertThatThrownBy(() -> cursors.decode(token, 2, 2)).isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> cursors.decode(token, 1, 0)).isInstanceOf(ProjectException.class);
  }

  @Test
  void otherApiDomainsCannotSupplyABookmarkCursor() {
    assertThatThrownBy(() -> cursors.decode(new StageActivityCursor(signing).encode(1, 2, 3), 1, 2))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(
            () ->
                cursors.decode(
                    new ActivityRecordCursor(signing)
                        .encode(1, 2, null, new ActivityRecordCursor.Position(Instant.EPOCH, 3)),
                    1,
                    2))
        .isInstanceOf(ProjectException.class);
  }

  @Test
  void payloadSignatureAndBase64EncodingCannotBeChanged() {
    String token = cursors.encode(1, 0, new Position(Instant.EPOCH, 3));
    String[] pieces = token.split("\\.");
    for (String changed :
        new String[] {
          "A" + token.substring(1),
          pieces[0] + "=." + pieces[1],
          token + "=",
          pieces[0] + "." + "A".repeat(43),
          token + ".x"
        })
      assertThatThrownBy(() -> cursors.decode(changed, 1, 0)).isInstanceOf(ProjectException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "x", "%%%%.%%%%", "a.b"})
  void malformedTokensAreRejected(String token) {
    assertThatThrownBy(() -> cursors.decode(token, 1, 0)).isInstanceOf(ProjectException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "api33-saved-v2|1|0|3|1970-01-01T00:00:00Z",
        "api33-saved-v1|01|0|3|1970-01-01T00:00:00Z",
        "api33-saved-v1|1|0|0|1970-01-01T00:00:00Z",
        "api33-saved-v1|1|0|-1|1970-01-01T00:00:00Z",
        "api33-saved-v1|1|0|9223372036854775808|1970-01-01T00:00:00Z",
        "api33-saved-v1|1|0|3|2026-11-19T00:00:00.123456789Z",
        "api33-saved-v1|1|0|3|bad-time",
        "api33-saved-v1|1|0|3|1970-01-01T00:00:00+00:00"
      })
  void signedButNoncanonicalOrUnsupportedPayloadsAreRejected(String payload) throws Exception {
    Mac derive = Mac.getInstance("HmacSHA256");
    derive.init(signing);
    var key =
        new SecretKeySpec(
            derive.doFinal("hobbytree/api-33/cursor-key/v1".getBytes(StandardCharsets.UTF_8)),
            "HmacSHA256");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(key);
    var base64 = Base64.getUrlEncoder().withoutPadding();
    String token =
        base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> cursors.decode(token, 1, 0)).isInstanceOf(ProjectException.class);
  }
}
