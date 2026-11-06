package com.hobbytree.hobbytree.domain.location.repository;

import com.hobbytree.hobbytree.domain.location.entity.LocationConsent;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationConsentRepository extends JpaRepository<LocationConsent, Long> {
  // The current consent state is just the latest row for this user+type (API-43 appends a new
  // row per decision instead of updating one in place).
  Optional<LocationConsent>
      findFirstByUserIdAndConsentTypeOrderByRecordedAtDescLocationConsentIdDesc(
          Long userId, LocationConsentType consentType);
}
