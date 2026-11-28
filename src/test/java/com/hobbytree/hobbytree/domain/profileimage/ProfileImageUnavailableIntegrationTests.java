package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = {"profile-image.bucket=", "profile-image.region="})
class ProfileImageUnavailableIntegrationTests extends IntegrationTestSupport {
  @Test
  void actualLazyGatewayReturns503WithoutIssuingUploadOrChangingProfile() throws Exception {
    var auth = result(login());
    long id = auth.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/users/me/profile-image/upload-url"))
            .header("Authorization", "Bearer " + auth.path("accessToken").asText())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"fileName\":\"profile.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":100}"))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD503_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM profile_image_uploads WHERE user_id=?", Long.class, id))
        .isZero();
  }
}
