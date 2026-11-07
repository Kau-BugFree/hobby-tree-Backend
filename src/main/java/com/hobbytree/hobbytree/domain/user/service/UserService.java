package com.hobbytree.hobbytree.domain.user.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.location.dto.LocationConsentResponse;
import com.hobbytree.hobbytree.domain.location.dto.LocationResponse;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentNotice;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import com.hobbytree.hobbytree.domain.location.repository.LocationConsentRepository;
import com.hobbytree.hobbytree.domain.location.repository.UserLocationRepository;
import com.hobbytree.hobbytree.domain.profileimage.config.ProfileImageProperties;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageService;
import com.hobbytree.hobbytree.domain.region.repository.RegionRepository;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.code.UserErrorCode;
import com.hobbytree.hobbytree.domain.user.dto.MyProfileResponse;
import com.hobbytree.hobbytree.domain.user.dto.NotificationSettingsResponse;
import com.hobbytree.hobbytree.domain.user.dto.UpdateNotificationSettingsRequest;
import com.hobbytree.hobbytree.domain.user.dto.UpdateNotificationSettingsResponse;
import com.hobbytree.hobbytree.domain.user.dto.UpdateProfileRequest;
import com.hobbytree.hobbytree.domain.user.dto.UpdateProfileResponse;
import com.hobbytree.hobbytree.domain.user.dto.WithdrawResponse;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.entity.UserPreference;
import com.hobbytree.hobbytree.domain.user.repository.UserPreferenceRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  // ACTIVE+PAUSED trees count against the 5-tree planting limit (API-19); ENDED frees the slot.
  private static final int MAX_TREE_COUNT = 5;
  private static final List<TreeStatus> SLOT_OCCUPYING_STATUSES =
      List.of(TreeStatus.ACTIVE, TreeStatus.PAUSED);

  private final UserRepository users;
  private final UserHobbyRepository userHobbies;
  private final RedisAuthStore store;
  private final Clock clock;
  private final UserPreferenceRepository preferences;
  private final UserLocationRepository userLocations;
  private final RegionRepository regions;
  private final LocationConsentRepository locationConsents;
  private final ProfileImageService profileImages;
  private final ProfileImageProperties profileImageProperties;

  public UserService(
      UserRepository users,
      UserHobbyRepository userHobbies,
      RedisAuthStore store,
      Clock clock,
      UserPreferenceRepository preferences,
      UserLocationRepository userLocations,
      RegionRepository regions,
      LocationConsentRepository locationConsents,
      ProfileImageService profileImages,
      ProfileImageProperties profileImageProperties) {
    this.users = users;
    this.userHobbies = userHobbies;
    this.store = store;
    this.clock = clock;
    this.preferences = preferences;
    this.userLocations = userLocations;
    this.regions = regions;
    this.locationConsents = locationConsents;
    this.profileImages = profileImages;
    this.profileImageProperties = profileImageProperties;
  }

  public MyProfileResponse getMyProfile(Long userId) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    long treeCount = userHobbies.countByUser_UserIdAndTreeStatusIn(userId, SLOT_OCCUPYING_STATUSES);
    return new MyProfileResponse(
        user.getUserId(),
        user.getNickname(),
        profileImageProperties.resolveUrl(user.getProfileImageKey()),
        user.getProvider(),
        user.getAgeRange(),
        user.getAccountStatus(),
        user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING,
        treeCount,
        MAX_TREE_COUNT,
        new NotificationSettingsResponse(
            preferences
                .findByUser_UserId(userId)
                .map(UserPreference::getNotificationFrequency)
                .orElse("NONE")),
        user.getCreatedAt(),
        buildLocation(userId),
        buildLocationConsent(userId));
  }

  private LocationResponse buildLocation(Long userId) {
    return userLocations
        .findByUserId(userId)
        .flatMap(
            location ->
                regions
                    .findById(location.getRegionId())
                    .map(
                        region ->
                            new LocationResponse(
                                region.getRegionId(),
                                region.getName(),
                                region.getFullName(),
                                region.getLevel().name(),
                                region.getParentRegionId(),
                                location.getSource().name(),
                                location.getUpdatedAt())))
        .orElse(null);
  }

  private LocationConsentResponse buildLocationConsent(Long userId) {
    return locationConsents
        .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescLocationConsentIdDesc(
            userId, LocationConsentType.NEARBY_EXPERIENCES)
        .map(
            consent ->
                new LocationConsentResponse(
                    consent.getConsentType().name(),
                    consent.getVersion(),
                    consent.isAgreed(),
                    consent.getRecordedAt()))
        .orElseGet(
            () ->
                new LocationConsentResponse(
                    LocationConsentType.NEARBY_EXPERIENCES.name(),
                    LocationConsentNotice.CURRENT_VERSION,
                    false,
                    null));
  }

  @Transactional
  public UpdateNotificationSettingsResponse updateNotificationSettings(
      Long userId, UpdateNotificationSettingsRequest request) {
    // The parent row also serializes creation when no preference row exists yet.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    UserPreference preference =
        preferences.findByUser_UserId(userId).orElseGet(() -> UserPreference.forUser(user));
    preference.updateNotificationFrequency(request.notificationFrequency());
    preferences.saveAndFlush(preference);
    return new UpdateNotificationSettingsResponse(
        userId,
        new NotificationSettingsResponse(preference.getNotificationFrequency()),
        preference.getUpdatedAt());
  }

  @Transactional
  public UpdateProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
    // Serialize partial updates so each request reads the previous transaction's changes.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    user.updateProfile(request.nickname(), request.ageRange());
    // profileImages.link verifies the key was issued to this user and the uploaded object
    // passes validation (size/content-type/magic bytes), then copies it to a final key -
    // linking the original upload key directly would let a still-valid presigned PUT URL
    // swap the content after verification. The final key, not the request's key, is what
    // gets stored.
    if (request.profileImageKey() != null) {
      String finalKey = profileImages.link(userId, request.profileImageKey());
      user.linkProfileImage(finalKey);
    }
    // updatedAt is DB-generated (@UpdateTimestamp(source = SourceType.DB)); flush now so the
    // UPDATE runs and Hibernate reads the fresh value back before the response is built.
    users.flush();
    return new UpdateProfileResponse(
        user.getUserId(),
        user.getNickname(),
        profileImageProperties.resolveUrl(user.getProfileImageKey()),
        user.getAgeRange(),
        user.getUpdatedAt());
  }

  @Transactional
  public WithdrawResponse withdraw(Long userId) {
    // AuthFilter already checked status on an unlocked read; re-check under lock in case it
    // changed (e.g. a concurrent withdraw/suspend) between that check and this transaction.
    User user =
        users
            .findForLogin(userId)
            .orElseThrow(() -> new ProjectException(UserErrorCode.WITHDRAWAL_CONFLICT));
    if (user.getAccountStatus() != AccountStatus.ACTIVE
        && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
      throw new ProjectException(UserErrorCode.WITHDRAWAL_CONFLICT);

    user.withdraw(clock.instant());
    store.revokeOnWithdrawal(userId);
    return new WithdrawResponse(
        user.getUserId(),
        user.getAccountStatus(),
        user.getWithdrawnAt(),
        user.getRestorableUntil());
  }
}
