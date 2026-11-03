package com.hobbytree.hobbytree.domain.notification.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.notification.code.NotificationErrorCode;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationItemResponse;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationListResponse;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationReadResponse;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationTargetResponse;
import com.hobbytree.hobbytree.domain.notification.entity.NotificationType;
import com.hobbytree.hobbytree.domain.notification.entity.UserNotification;
import com.hobbytree.hobbytree.domain.notification.repository.UserNotificationRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;

  private final UserRepository users;
  private final UserNotificationRepository notifications;
  private final Clock clock;

  public NotificationService(
      UserRepository users, UserNotificationRepository notifications, Clock clock) {
    this.users = users;
    this.notifications = notifications;
    this.clock = clock;
  }

  public NotificationListResponse list(
      Long userId, Boolean isRead, String type, String cursor, Integer size) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Separate from AuthFilter's shared status gate, which still admits PENDING_ONBOARDING.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    String notificationType = parseType(type);
    int pageSize = parseSize(size);
    Cursor position = decodeCursor(cursor);

    List<UserNotification> rows =
        notifications.findPage(
            userId,
            notificationType != null,
            notificationType == null ? "" : notificationType,
            isRead != null,
            isRead != null && isRead,
            position != null,
            position == null ? Instant.EPOCH : position.createdAt(),
            position == null ? 0L : position.id(),
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<UserNotification> page = hasNext ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
    long unreadCount = notifications.countByUser_UserIdAndReadAtIsNull(userId);

    return new NotificationListResponse(
        page.stream().map(this::toItem).toList(), nextCursor, hasNext, unreadCount);
  }

  @Transactional
  public NotificationReadResponse markRead(Long userId, Long notificationId) {
    // Lock the user row for the whole transaction so concurrent read requests for this user
    // serialize: without it, two requests for the same notification could both see readAt ==
    // null and overwrite each other's readAt, and two requests for different notifications
    // could both compute unreadCount from the other's uncommitted change.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Separate from AuthFilter's shared status gate, which still admits PENDING_ONBOARDING.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    UserNotification notification =
        notifications
            .findByNotificationIdAndUser_UserId(notificationId, userId)
            .orElseThrow(() -> new ProjectException(NotificationErrorCode.NOT_FOUND));
    // created_at/read_at are TIMESTAMPTZ (microsecond precision in Postgres); truncate here so
    // the in-memory value returned in this response matches what actually gets persisted,
    // instead of a nanosecond-precision clock reading that a later read would never reproduce.
    notification.markRead(clock.instant().truncatedTo(ChronoUnit.MICROS));

    return new NotificationReadResponse(
        notification.getNotificationId(),
        true,
        notification.getReadAt(),
        notifications.countByUser_UserIdAndReadAtIsNull(userId));
  }

  private NotificationItemResponse toItem(UserNotification n) {
    NotificationTargetResponse target;
    if (n.getRelatedUserHobby() != null) {
      target =
          new NotificationTargetResponse("USER_HOBBY", n.getRelatedUserHobby().getUserHobbyId());
    } else if (n.getRelatedActivity() != null) {
      target = new NotificationTargetResponse("ACTIVITY", n.getRelatedActivity().getActivityId());
    } else {
      target = null;
    }
    return new NotificationItemResponse(
        n.getNotificationId(),
        n.getNotificationType(),
        n.getTitle(),
        n.getMessage(),
        target,
        n.getReadAt() != null,
        n.getReadAt(),
        n.getCreatedAt());
  }

  private String parseType(String type) {
    if (type == null) return null;
    try {
      return NotificationType.valueOf(type).name();
    } catch (IllegalArgumentException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private int parseSize(Integer size) {
    if (size == null) return DEFAULT_SIZE;
    if (size < 1 || size > MAX_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return size;
  }

  private record Cursor(Instant createdAt, Long id) {}

  // Opaque cursor: base64("{createdAt.toString()}_{notificationId}"). created_at is a
  // TIMESTAMPTZ (microsecond precision in Postgres); encoding the full Instant (not
  // epochMilli, which truncates sub-millisecond precision) keeps "createdAt < cursor" from
  // skipping notifications that fall between the truncated and the real timestamp.
  private Cursor decodeCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("_", 2);
      return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (RuntimeException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private String encodeCursor(UserNotification last) {
    String raw = last.getCreatedAt() + "_" + last.getNotificationId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
