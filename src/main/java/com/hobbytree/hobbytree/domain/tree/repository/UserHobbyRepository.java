package com.hobbytree.hobbytree.domain.tree.repository;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserHobbyRepository extends JpaRepository<UserHobby, Long> {
  // Lock only the owned tree, after the API-19-compatible user lock. No catalog row lock.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT uh FROM UserHobby uh WHERE uh.userHobbyId = :userHobbyId AND uh.user.userId = :userId")
  Optional<UserHobby> findOwnedForStatusChange(
      @Param("userHobbyId") Long userHobbyId, @Param("userId") Long userId);

  @Query(
      """
      SELECT uh FROM UserHobby uh JOIN FETCH uh.hobby
      WHERE uh.userHobbyId = :userHobbyId AND uh.user.userId = :userId
      """)
  Optional<UserHobby> findOwnedByIdWithHobby(
      @Param("userHobbyId") Long userHobbyId, @Param("userId") Long userId);

  boolean existsByUser_UserIdAndHobby_HobbyId(Long userId, Long hobbyId);

  @Query(
      """
      SELECT uh FROM UserHobby uh
      JOIN FETCH uh.hobby
      WHERE uh.user.userId = :userId
        AND (:hasStatus = FALSE OR uh.treeStatus = :status)
        AND (:hasCursor = FALSE
             OR uh.plantedAt < :beforePlantedAt
             OR (uh.plantedAt = :beforePlantedAt AND uh.userHobbyId < :beforeId))
      ORDER BY uh.plantedAt DESC, uh.userHobbyId DESC
      """)
  List<UserHobby> findOwnedWithHobby(
      @Param("userId") Long userId,
      @Param("hasStatus") boolean hasStatus,
      @Param("status") TreeStatus status,
      @Param("hasCursor") boolean hasCursor,
      @Param("beforePlantedAt") Instant beforePlantedAt,
      @Param("beforeId") long beforeId,
      Pageable pageable);

  long countByUser_UserIdAndTreeStatusIn(Long userId, Collection<TreeStatus> treeStatuses);

  // API-16's isPlanted/userHobbyId: the caller's own tree per candidate hobby, if any.
  List<UserHobby> findByUser_UserIdAndHobby_HobbyIdIn(Long userId, Collection<Long> hobbyIds);

  // API-16's sort=POPULAR. ACTIVE+PAUSED only, matching the same "counts as owned" definition
  // used for the 5-slot limit (see hobbytree-team-decisions-2026-11-04).
  @Query(
      """
      SELECT uh.hobby.hobbyId AS hobbyId, COUNT(uh) AS plantedCount
      FROM UserHobby uh
      WHERE uh.hobby.hobbyId IN :hobbyIds AND uh.treeStatus IN ('ACTIVE','PAUSED')
      GROUP BY uh.hobby.hobbyId
      """)
  List<HobbyPopularity> countPlantedByHobbyIds(@Param("hobbyIds") Collection<Long> hobbyIds);

  interface HobbyPopularity {
    Long getHobbyId();

    Long getPlantedCount();
  }

  // API-14's "이미 보유한 취미" exclusion: every hobby this user has ever owned, regardless of
  // treeStatus (ENDED included) - broader than the ACTIVE+PAUSED "counts as owned" definition
  // used for the 5-slot limit and API-16's isPlanted, since a recommendation shouldn't re-suggest
  // a hobby the user already tried and gave up on either.
  @Query("SELECT uh.hobby.hobbyId FROM UserHobby uh WHERE uh.user.userId = :userId")
  Set<Long> findAllOwnedHobbyIds(@Param("userId") Long userId);
}
