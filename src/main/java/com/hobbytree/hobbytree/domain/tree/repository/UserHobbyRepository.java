package com.hobbytree.hobbytree.domain.tree.repository;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserHobbyRepository extends JpaRepository<UserHobby, Long> {
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
}
