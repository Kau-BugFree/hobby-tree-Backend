package com.hobbytree.hobbytree.domain.hobby.repository;

import com.hobbytree.hobbytree.domain.hobby.entity.HobbyStage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HobbyStageRepository extends JpaRepository<HobbyStage, Long> {
  @Query(
      """
      SELECT hs.hobby.hobbyId AS hobbyId, hs.stageNumber AS stageNumber, hs.stageName AS stageName
      FROM HobbyStage hs
      WHERE hs.hobby.hobbyId IN :hobbyIds
      """)
  List<StageName> findStageNamesByHobbyIds(@Param("hobbyIds") Collection<Long> hobbyIds);

  interface StageName {
    Long getHobbyId();

    short getStageNumber();

    String getStageName();
  }
}
