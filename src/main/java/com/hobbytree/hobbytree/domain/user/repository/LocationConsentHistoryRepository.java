package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.LocationConsentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationConsentHistoryRepository
    extends JpaRepository<LocationConsentHistory, Long> {}
