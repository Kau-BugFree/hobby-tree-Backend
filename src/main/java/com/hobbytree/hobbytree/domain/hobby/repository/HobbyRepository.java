package com.hobbytree.hobbytree.domain.hobby.repository;

import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HobbyRepository extends JpaRepository<Hobby, Long> {}
