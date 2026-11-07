package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.ExperienceReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperienceReservationRepository
    extends JpaRepository<ExperienceReservation, Long> {}
