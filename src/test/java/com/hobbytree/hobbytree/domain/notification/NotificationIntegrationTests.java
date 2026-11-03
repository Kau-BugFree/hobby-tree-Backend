package com.hobbytree.hobbytree.domain.notification;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.*;
import java.net.http.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// JUnit5 does not reliably inherit @EnabledIfEnvironmentVariable from IntegrationTestSupport in
// a plain build, so it must be repeated here or this class's tests try (and fail) to run.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class NotificationIntegrationTests extends IntegrationTestSupport {

  HttpResponse<String> getNotifications(String accessToken, String query) throws Exception {
    String url =
        "http://127.0.0.1:" + port + "/api/v1/notifications" + (query == null ? "" : "?" + query);
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void seedNotification(long userId, String type, String title, int minutesAgo, boolean read) {
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at, read_at) "
            + "VALUES (?, ?, ?, ?, now() - (? || ' minutes')::interval, CASE WHEN ? THEN now() ELSE NULL END)",
        userId,
        type,
        title,
        title + " 본문",
        minutesAgo,
        read);
  }

  long seedNotificationReturningId(long userId, String type, String title, int minutesAgo) {
    return jdbc.queryForObject(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, ?, ?, ?, now() - (? || ' minutes')::interval) RETURNING notification_id",
        Long.class,
        userId,
        type,
        title,
        title + " 본문",
        minutesAgo);
  }

  HttpResponse<String> markNotificationRead(String accessToken, String notificationId)
      throws Exception {
    String url = "http://127.0.0.1:" + port + "/api/v1/notifications/" + notificationId + "/read";
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void notificationListRejectsPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = getNotifications(signedIn.path("accessToken").asText(), null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void notificationListFiltersByReadStatusAndType() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    seedNotification(id, "TREE_GROWTH", "나무 성장", 10, false);
    seedNotification(id, "ANNOUNCEMENT", "공지", 5, true);

    // A second user's (GOOGLE fixture, so it's a distinct account) unread notification must
    // never leak into the first user's items or unreadCount below.
    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    seedNotification(otherId, "TREE_GROWTH", "다른 사용자 알림", 1, false);

    var all = result(getNotifications(access, null));
    assertThat(all.path("items")).hasSize(2);
    assertThat(all.path("unreadCount").asLong()).isEqualTo(1);

    var unreadOnly = result(getNotifications(access, "isRead=false"));
    assertThat(unreadOnly.path("items")).hasSize(1);
    assertThat(unreadOnly.path("items").get(0).path("type").asText()).isEqualTo("TREE_GROWTH");
    assertThat(unreadOnly.path("items").get(0).path("isRead").asBoolean()).isFalse();
    assertThat(unreadOnly.path("items").get(0).path("target").isNull()).isTrue();

    var byType = result(getNotifications(access, "type=ANNOUNCEMENT"));
    assertThat(byType.path("items")).hasSize(1);
    assertThat(byType.path("items").get(0).path("title").asText()).isEqualTo("공지");
  }

  @Test
  void notificationListPaginatesWithCursor() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    for (int i = 0; i < 3; i++) seedNotification(id, "ANNOUNCEMENT", "공지" + i, i, false);

    var firstPage = result(getNotifications(access, "size=2"));
    assertThat(firstPage.path("items")).hasSize(2);
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    assertThat(firstPage.path("items").get(0).path("title").asText()).isEqualTo("공지0");
    assertThat(firstPage.path("items").get(1).path("title").asText()).isEqualTo("공지1");
    String cursor = firstPage.path("nextCursor").asText();

    var secondPage = result(getNotifications(access, "size=2&cursor=" + cursor));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("hasNext").asBoolean()).isFalse();
    assertThat(secondPage.path("items").get(0).path("title").asText()).isEqualTo("공지2");
  }

  @Test
  void notificationListCursorPreservesMicrosecondPrecision() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    // Same millisecond (.123), different microseconds: truncating the cursor to epoch millis
    // would make "older" indistinguishable from the "newer" boundary and drop it from page 2.
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, 'ANNOUNCEMENT', 'newer', 'newer body', TIMESTAMPTZ '2026-01-01 00:00:00.123456+00')",
        id);
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, 'ANNOUNCEMENT', 'older', 'older body', TIMESTAMPTZ '2026-01-01 00:00:00.123100+00')",
        id);

    var firstPage = result(getNotifications(access, "size=1"));
    assertThat(firstPage.path("items")).hasSize(1);
    assertThat(firstPage.path("items").get(0).path("title").asText()).isEqualTo("newer");
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.path("nextCursor").asText();

    var secondPage = result(getNotifications(access, "size=1&cursor=" + cursor));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("items").get(0).path("title").asText()).isEqualTo("older");
  }

  @Test
  void notificationListOrdersByIdDescendingWhenCreatedAtTies() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();

    // All rows share one createdAt: only "notificationId DESC" (and the cursor's
    // "createdAt = :beforeCreatedAt AND notificationId < :beforeId" tiebreak) can order or page
    // them deterministically. Losing that tiebreak could repeat or skip rows across a page
    // boundary without this test noticing, since every other test here uses distinct timestamps.
    for (int i = 0; i < 5; i++) {
      jdbc.update(
          "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
              + "VALUES (?, 'ANNOUNCEMENT', ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00')",
          id,
          "tie" + i,
          "tie" + i + " 본문");
    }
    List<Long> expectedIds =
        jdbc.queryForList(
            "SELECT notification_id FROM user_notifications WHERE user_id = ? ORDER BY notification_id DESC",
            Long.class,
            id);

    List<Long> collectedIds = new ArrayList<>();
    String cursor = null;
    boolean hasNext = true;
    while (hasNext) {
      String query = "size=2" + (cursor == null ? "" : "&cursor=" + cursor);
      var page = result(getNotifications(access, query));
      for (var item : page.path("items")) collectedIds.add(item.path("notificationId").asLong());
      hasNext = page.path("hasNext").asBoolean();
      cursor = page.path("nextCursor").asText();
    }

    assertThat(collectedIds).isEqualTo(expectedIds);
  }

  @Test
  void notificationListRejectsInvalidQueryParams() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();

    assertThat(getNotifications(access, "type=NOT_A_TYPE").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "size=0").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "size=51").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "cursor=YWJj").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "isRead=notabool").statusCode()).isEqualTo(400);
  }

  @Test
  void notificationMarkReadSetsReadAtAndIsIdempotent() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    long notificationId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지", 5);

    var first = result(markNotificationRead(access, String.valueOf(notificationId)));
    assertThat(first.path("notificationId").asLong()).isEqualTo(notificationId);
    assertThat(first.path("isRead").asBoolean()).isTrue();
    assertThat(first.path("readAt").isNull()).isFalse();
    assertThat(first.path("unreadCount").asLong()).isEqualTo(0);
    String firstReadAt = first.path("readAt").asText();

    // A repeat request must keep the first readAt and not double-decrement unreadCount.
    var second = result(markNotificationRead(access, String.valueOf(notificationId)));
    assertThat(second.path("readAt").asText()).isEqualTo(firstReadAt);
    assertThat(second.path("unreadCount").asLong()).isEqualTo(0);
  }

  @Test
  void notificationMarkReadRejectsOtherUsersNotification() throws Exception {
    var ownerSignedIn = result(login());
    long ownerId = ownerSignedIn.path("userId").asLong();
    createdUsers.add(ownerId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", ownerId);
    long notificationId = seedNotificationReturningId(ownerId, "ANNOUNCEMENT", "공지", 5);

    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);

    var response =
        markNotificationRead(
            otherSignedIn.path("accessToken").asText(), String.valueOf(notificationId));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("NOTIFICATION404_1");
  }

  @Test
  void notificationMarkReadRejectsPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = markNotificationRead(signedIn.path("accessToken").asText(), "1");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void notificationMarkReadRejectsNonNumericId() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);

    assertThat(
            markNotificationRead(signedIn.path("accessToken").asText(), "not-a-number")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void notificationMarkReadSerializesConcurrentRequestsForSameNotification() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    long notificationId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지", 5);

    // Two requests racing on the SAME notification must not both see readAt == null and each
    // record their own timestamp; the user-row lock in markRead() forces them to serialize.
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return markNotificationRead(access, String.valueOf(notificationId));
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var first = a.get(30, TimeUnit.SECONDS);
      var second = b.get(30, TimeUnit.SECONDS);
      // Checked before comparing readAt below: two requests that both failed would both carry
      // result: null, which would trivially satisfy that equality without proving anything.
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(second.statusCode()).isEqualTo(200);
      assertThat(result(first).path("readAt").asText())
          .isEqualTo(result(second).path("readAt").asText());
    }
  }

  @Test
  void notificationMarkReadNeverDoubleCountsUnreadAcrossConcurrentRequests() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    long firstId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지1", 10);
    long secondId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지2", 5);

    // Two requests racing on DIFFERENT notifications of the same user must not both read the
    // other's uncommitted unreadCount decrement; the user-row lock serializes them so at least
    // one response reflects the other's already-committed change (never both reporting 1 when
    // the real remaining unread count is 0).
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> callFirst =
          () -> {
            start.await();
            return markNotificationRead(access, String.valueOf(firstId));
          };
      Callable<HttpResponse<String>> callSecond =
          () -> {
            start.await();
            return markNotificationRead(access, String.valueOf(secondId));
          };
      var a = executor.submit(callFirst);
      var b = executor.submit(callSecond);
      start.countDown();
      var responseA = a.get(30, TimeUnit.SECONDS);
      var responseB = b.get(30, TimeUnit.SECONDS);
      // Checked before reading unreadCount below: a failed request would carry result: null,
      // so asLong() on a missing field would silently read as 0 and mask a real failure.
      assertThat(responseA.statusCode()).isEqualTo(200);
      assertThat(responseB.statusCode()).isEqualTo(200);
      assertThat(
              List.of(
                  result(responseA).path("unreadCount").asLong(),
                  result(responseB).path("unreadCount").asLong()))
          .contains(0L);
    }

    Long remainingUnread =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_notifications WHERE user_id = ? AND read_at IS NULL",
            Long.class,
            id);
    assertThat(remainingUnread).isEqualTo(0L);
  }

  @Test
  void notificationMarkReadRejectsAfterWithdrawalCommitsWhileWaitingOnTheUserLock()
      throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    long notificationId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지", 5);

    // AuthFilter's own status check is an UNLOCKED read, separate from the user-row lock
    // markRead takes. Hold that lock open on our own connection so markRead's request is forced
    // to wait for it after AuthFilter has already read ACTIVE, then withdraw and commit while it
    // waits — reproducing the exact window the TOCTOU review described, instead of relying on
    // two threads racing each other and hoping to land in that window.
    try (Connection lockHolder = dataSource.getConnection()) {
      lockHolder.setAutoCommit(false);
      try (var select =
          lockHolder.prepareStatement(
              "SELECT account_status FROM users WHERE user_id = ? FOR UPDATE")) {
        select.setLong(1, id);
        select.executeQuery();
      }

      try (var executor = Executors.newSingleThreadExecutor()) {
        Future<HttpResponse<String>> markReadFuture =
            executor.submit(() -> markNotificationRead(access, String.valueOf(notificationId)));
        // Give the request time to pass AuthFilter's unlocked read and start waiting on the
        // row lock we're holding, before we withdraw underneath it.
        Thread.sleep(300);

        try (var update =
            lockHolder.prepareStatement(
                "UPDATE users SET account_status='WITHDRAWN', withdrawn_at=now(),"
                    + " restorable_until=now()+interval '720 hours',"
                    + " status_before_withdrawal='ACTIVE' WHERE user_id = ?")) {
          update.setLong(1, id);
          update.executeUpdate();
        }
        lockHolder.commit();

        var response = markReadFuture.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_3");
      }
    }
  }
}
