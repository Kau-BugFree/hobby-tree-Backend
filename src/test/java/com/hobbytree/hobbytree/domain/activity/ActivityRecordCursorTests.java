package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCursor;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCursor.Position;
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

class ActivityRecordCursorTests {
  private final SecretKeySpec signing = new SecretKeySpec(new byte[64], "HmacSHA256");
  private final ActivityRecordCursor cursors = new ActivityRecordCursor(signing);

  @Test
  void roundTripsMicrosecondsAndLegacyNullPositions() {
    for (var position :
        new Position[] {
          new Position(Instant.parse("2026-11-19T01:02:03.123456Z"), 123), new Position(null, 124)
        }) {
      String token = cursors.encode(1, 2, "PARTIAL", position);
      assertThat(cursors.decode(token, 1, 2, "PARTIAL")).isEqualTo(position);
    }
    assertThat(cursors.decode(null, 1, 2, null)).isNull();
  }

  @Test
  void signedScopeAndApiSpecificKeysRejectCrossReuse() {
    String token = cursors.encode(1, 2, null, new Position(null, 3));
    assertThatThrownBy(() -> cursors.decode(token, 2, 2, null))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> cursors.decode(token, 1, 3, null))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> cursors.decode(token, 1, 2, "PARTIAL"))
        .isInstanceOf(ProjectException.class);
    var stages = new StageActivityCursor(signing);
    assertThatThrownBy(() -> cursors.decode(stages.encode(1, 2, null, 3), 1, 2, null))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> stages.decode(token, 1, 2)).isInstanceOf(ProjectException.class);
  }

  @Test
  void payloadSignatureAndCanonicalEncodingCannotBeChanged() {
    String token = cursors.encode(1, 2, null, new Position(null, 3));
    String[] parts = token.split("\\.");
    for (String changed :
        new String[] {
          "A" + token.substring(1),
          parts[0] + "." + "A".repeat(43),
          parts[0] + "=." + parts[1],
          token + "=",
          token + ".x"
        })
      assertThatThrownBy(() -> cursors.decode(changed, 1, 2, null))
          .isInstanceOf(ProjectException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "x", "%%%%.%%%%", "a.b", "api30-started-v1|1|2|ALL|~|3"})
  void malformedAndUnsignedTokensAreRejected(String token) {
    assertThatThrownBy(() -> cursors.decode(token, 1, 2, null))
        .isInstanceOf(ProjectException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "api30-started-v2|1|2|ALL|~|3",
        "api30-started-v1|01|2|ALL|~|3",
        "api30-started-v1|1|2|ALL|~|0",
        "api30-started-v1|1|2|ALL|~|-1",
        "api30-started-v1|1|2|ALL|~|9223372036854775808",
        "api30-started-v1|1|2|ALL|2026-11-19T01:02:03.123456789Z|3",
        "api30-started-v1|1|2|ALL|invalid|3",
        "api30-started-v1|1|2|ALL|~|3|extra"
      })
  void evenSignedNoncanonicalOrUnsupportedPositionsAreRejected(String payload) throws Exception {
    Mac derivation = Mac.getInstance("HmacSHA256");
    derivation.init(signing);
    var key =
        new SecretKeySpec(
            derivation.doFinal("hobbytree/api-30/cursor-key/v1".getBytes(StandardCharsets.UTF_8)),
            "HmacSHA256");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(key);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> cursors.decode(token, 1, 2, null))
        .isInstanceOf(ProjectException.class);
  }
}
