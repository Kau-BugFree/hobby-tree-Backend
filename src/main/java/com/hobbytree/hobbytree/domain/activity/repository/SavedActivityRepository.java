package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.SavedActivity;
import com.hobbytree.hobbytree.domain.activity.entity.SavedActivityId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedActivityRepository extends JpaRepository<SavedActivity, SavedActivityId> {}
