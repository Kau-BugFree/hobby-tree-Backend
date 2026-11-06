package com.hobbytree.hobbytree.domain.location.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.location.code.LocationErrorCode;
import com.hobbytree.hobbytree.domain.location.dto.LocationResponse;
import com.hobbytree.hobbytree.domain.location.dto.SetLocationRequest;
import com.hobbytree.hobbytree.domain.location.dto.SetLocationResponse;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsent;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import com.hobbytree.hobbytree.domain.location.entity.LocationSource;
import com.hobbytree.hobbytree.domain.location.entity.UserLocation;
import com.hobbytree.hobbytree.domain.location.repository.LocationConsentRepository;
import com.hobbytree.hobbytree.domain.location.repository.UserLocationRepository;
import com.hobbytree.hobbytree.domain.region.entity.Region;
import com.hobbytree.hobbytree.domain.region.entity.RegionLevel;
import com.hobbytree.hobbytree.domain.region.repository.RegionRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserLocationService {
  private final UserRepository users;
  private final UserLocationRepository userLocations;
  private final RegionRepository regions;
  private final LocationConsentRepository consents;
  private final KakaoCoordinateRegionResolver kakaoResolver;

  @Transactional
  public SetLocationResponse set(Long userId, SetLocationRequest request) {
    // Serialize same-user requests, including the first one with no row yet.
    User user = lockActiveUser(userId);
    LocationSource source = parseSource(request.source());

    Long regionId =
        source == LocationSource.MANUAL
            ? resolveManualRegionId(request)
            : resolveDeviceRegionId(userId, request);

    Region region =
        regions
            .findById(regionId)
            .filter(r -> r.getLevel() == RegionLevel.SIGUNGU)
            .orElseThrow(() -> new ProjectException(LocationErrorCode.REGION_NOT_FOUND));

    UserLocation location =
        userLocations
            .findByUserId(user.getUserId())
            .map(
                existing -> {
                  existing.replace(region.getRegionId(), source);
                  return existing;
                })
            .orElseGet(() -> UserLocation.of(user.getUserId(), region.getRegionId(), source));
    // updatedAt is DB-generated (@UpdateTimestamp(source = SourceType.DB)); flush now so the
    // INSERT/UPDATE runs and Hibernate reads the fresh value back before the response is built.
    userLocations.saveAndFlush(location);

    return new SetLocationResponse(user.getUserId(), toLocationResponse(region, location));
  }

  private Long resolveManualRegionId(SetLocationRequest request) {
    if (request.regionId() == null
        || request.latitude() != null
        || request.longitude() != null
        || request.consentVersion() != null)
      throw new ProjectException(LocationErrorCode.INVALID_SOURCE_FIELDS);
    return request.regionId();
  }

  private Long resolveDeviceRegionId(Long userId, SetLocationRequest request) {
    if (request.regionId() != null
        || request.latitude() == null
        || request.longitude() == null
        || request.consentVersion() == null
        || request.latitude() < -90
        || request.latitude() > 90
        || request.longitude() < -180
        || request.longitude() > 180)
      throw new ProjectException(LocationErrorCode.INVALID_SOURCE_FIELDS);

    var consent =
        consents
            .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescLocationConsentIdDesc(
                userId, LocationConsentType.NEARBY_EXPERIENCES)
            .filter(LocationConsent::isAgreed)
            .orElseThrow(() -> new ProjectException(LocationErrorCode.CONSENT_MISSING));
    if (!consent.getVersion().equals(request.consentVersion()))
      throw new ProjectException(LocationErrorCode.CONSENT_VERSION_MISMATCH);

    return kakaoResolver.resolveSigunguRegionId(request.latitude(), request.longitude());
  }

  private LocationSource parseSource(String source) {
    try {
      return LocationSource.valueOf(source);
    } catch (RuntimeException e) {
      throw new ProjectException(LocationErrorCode.INVALID_SOURCE_FIELDS);
    }
  }

  private LocationResponse toLocationResponse(Region region, UserLocation location) {
    return new LocationResponse(
        region.getRegionId(),
        region.getName(),
        region.getFullName(),
        region.getLevel().name(),
        region.getParentRegionId(),
        location.getSource().name(),
        location.getUpdatedAt());
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
