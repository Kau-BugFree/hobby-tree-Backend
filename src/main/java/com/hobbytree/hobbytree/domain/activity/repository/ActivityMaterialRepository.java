package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.ActivityMaterial;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityMaterialRepository extends JpaRepository<ActivityMaterial, Long> {
  // API-17's starterMaterials: the materials of the hobby's 입문 활동 (HobbyService resolves
  // which activity that is, the same one API-16 uses for minDurationMinutes/minCost/difficulty).
  List<ActivityMaterial> findByActivity_ActivityIdOrderByActivityMaterialIdAsc(Long activityId);
}
