package com.hobbytree.hobbytree.domain.location.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.location.code.LocationErrorCode;
import com.hobbytree.hobbytree.domain.location.dto.RecordLocationConsentRequest;
import com.hobbytree.hobbytree.domain.location.dto.RecordLocationConsentResponse;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsent;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentNotice;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import com.hobbytree.hobbytree.domain.location.entity.LocationSource;
import com.hobbytree.hobbytree.domain.location.repository.LocationConsentRepository;
import com.hobbytree.hobbytree.domain.location.repository.UserLocationRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocationConsentService {
  private final UserRepository users;
  private final LocationConsentRepository consents;
  private final UserLocationRepository userLocations;

  @Transactional
  public RecordLocationConsentResponse record(Long userId, RecordLocationConsentRequest request) {
    // Lock the user row for the same reason as UserLocationService.set: serialize concurrent
    // requests from the same user, including the first one with no consent row yet.
    User user = lockActiveUser(userId);

    if (request.consentType() == null
        || request.consentType().isBlank()
        || request.version() == null
        || request.version().isBlank()
        || request.agreed() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    LocationConsentType consentType = parseConsentType(request.consentType());
    if (!LocationConsentNotice.CURRENT_VERSION.equals(request.version()))
      throw new ProjectException(LocationErrorCode.NOTICE_VERSION_CHANGED);

    // Append-only: every decision is a new row, never an update of a prior one.
    LocationConsent consent =
        LocationConsent.of(user.getUserId(), consentType, request.version(), request.agreed());
    // recordedAt is DB-generated (@CreationTimestamp(source = SourceType.DB)); flush now so
    // the INSERT runs and Hibernate reads the fresh value back before the response is built.
    consents.saveAndFlush(consent);

    boolean locationCleared = !request.agreed() && clearDeviceLocation(user.getUserId());

    return new RecordLocationConsentResponse(
        consent.getLocationConsentId(),
        consent.getConsentType().name(),
        consent.getVersion(),
        consent.isAgreed(),
        consent.getRecordedAt(),
        locationCleared);
  }

  // Withdrawing consent blocks future DEVICE conversions (UserLocationService.resolveDeviceRegionId
  // already checks for a current agreed consent) and also clears any location the user already
  // has set via DEVICE. A MANUAL location is untouched - it was never conditioned on consent.
  private boolean clearDeviceLocation(Long userId) {
    return userLocations
        .findByUserId(userId)
        .filter(location -> location.getSource() == LocationSource.DEVICE)
        .map(
            location -> {
              userLocations.delete(location);
              return true;
            })
        .orElse(false);
  }

  private LocationConsentType parseConsentType(String value) {
    try {
      return LocationConsentType.valueOf(value);
    } catch (RuntimeException e) {
      throw new ProjectException(LocationErrorCode.UNSUPPORTED_CONSENT_TYPE);
    }
  }

  private User lockActiveUser(Long userId) {
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
    return user;
  }
}
