package com.hobbytree.hobbytree.domain.notification.repository;

import com.hobbytree.hobbytree.domain.notification.entity.UserNotification;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

  // Each optional filter gets its own "has*" boolean instead of comparing the real value to
  // NULL: Postgres cannot infer a bare "?" parameter's type from "x IS NULL" alone (seen as
  // "could not determine data type of parameter" for the Instant cursor bound), so every
  // value parameter here is always non-null and typed by its other, real comparison.
  // Keyset pagination: Pageable here only bounds the page size (its own Sort is unused), since
  // ordering is fixed by API-44 ("생성 시각·알림 ID 내림차순") and expressed in the JPQL below.
  @Query(
      """
      SELECT n FROM UserNotification n
      WHERE n.user.userId = :userId
        AND (:hasType = FALSE OR n.notificationType = :type)
        AND (:hasIsRead = FALSE
             OR (:isRead = TRUE AND n.readAt IS NOT NULL)
             OR (:isRead = FALSE AND n.readAt IS NULL))
        AND (:hasCursor = FALSE
             OR n.createdAt < :beforeCreatedAt
             OR (n.createdAt = :beforeCreatedAt AND n.notificationId < :beforeId))
      ORDER BY n.createdAt DESC, n.notificationId DESC
      """)
  List<UserNotification> findPage(
      @Param("userId") Long userId,
      @Param("hasType") boolean hasType,
      @Param("type") String type,
      @Param("hasIsRead") boolean hasIsRead,
      @Param("isRead") boolean isRead,
      @Param("hasCursor") boolean hasCursor,
      @Param("beforeCreatedAt") Instant beforeCreatedAt,
      @Param("beforeId") Long beforeId,
      Pageable pageable);

  long countByUser_UserIdAndReadAtIsNull(Long userId);
}
