package com.hobbytree.hobbytree.domain.user.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategoryCatalog;
import com.hobbytree.hobbytree.domain.location.dto.LocationConsentResponse;
import com.hobbytree.hobbytree.domain.location.dto.LocationResponse;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentNotice;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import com.hobbytree.hobbytree.domain.location.repository.LocationConsentRepository;
import com.hobbytree.hobbytree.domain.location.repository.UserLocationRepository;
import com.hobbytree.hobbytree.domain.onboarding.code.OnboardingErrorCode;
import com.hobbytree.hobbytree.domain.onboarding.dto.AgreementRequest;
import com.hobbytree.hobbytree.domain.onboarding.dto.CompleteOnboardingRequest;
import com.hobbytree.hobbytree.domain.onboarding.dto.CompleteOnboardingResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.TermsResponse;
import com.hobbytree.hobbytree.domain.onboarding.entity.UserTermsAgreement;
import com.hobbytree.hobbytree.domain.onboarding.repository.UserTermsAgreementRepository;
import com.hobbytree.hobbytree.domain.onboarding.service.OnboardingService;
import com.hobbytree.hobbytree.domain.preference.code.PreferenceErrorCode;
import com.hobbytree.hobbytree.domain.preference.dto.UpdatePreferencesRequest;
import com.hobbytree.hobbytree.domain.preference.dto.UpdatePreferencesResponse;
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
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  // ACTIVE+PAUSED trees count against the 5-tree planting limit (API-19); ENDED frees the slot.
  private static final int MAX_TREE_COUNT = 5;
  private static final List<TreeStatus> SLOT_OCCUPYING_STATUSES =
      List.of(TreeStatus.ACTIVE, TreeStatus.PAUSED);

  // API-11's allowed value sets, matching API-10's own option lists exactly.
  private static final Set<String> BUDGET_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
  private static final Set<Integer> AVAILABLE_TIME_MINUTES = Set.of(15, 30, 60, 120);
  private static final Set<String> INDOOR_OUTDOOR_VALUES = Set.of("INDOOR", "OUTDOOR", "ANY");
  private static final Set<String> DIFFICULTY_PREFERENCES =
      Set.of("BEGINNER", "INTERMEDIATE", "ANY");
  private static final int MAX_PREFERRED_CATEGORIES = 3;

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
  private final UserTermsAgreementRepository termsAgreements;

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
      ProfileImageProperties profileImageProperties,
      UserTermsAgreementRepository termsAgreements) {
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
    this.termsAgreements = termsAgreements;
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

  public UpdatePreferencesResponse getPreferences(Long userId) {
    users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // A row can already exist here for a user who has only ever touched
    // notificationFrequency (API-46) - preferencesVersion==0 (not row absence) is what "no
    // preferences saved yet" actually means, same as updatePreferences' expectedVersion check.
    UserPreference preference =
        preferences
            .findByUser_UserId(userId)
            .filter(p -> p.getPreferencesVersion() != 0)
            .orElseThrow(() -> new ProjectException(PreferenceErrorCode.NOT_FOUND));
    return new UpdatePreferencesResponse(
        preference.getOptionsVersion(),
        preference.getBudgetLevel(),
        preference.getAvailableTimeMinutes(),
        preference.getIndoorOutdoor(),
        preference.getDifficultyPreference(),
        parseCategoryIds(preference.getPreferredCategoryIds()),
        userId,
        preference.getPreferencesVersion(),
        preference.getUpdatedAt());
  }

  @Transactional
  public UpdatePreferencesResponse updatePreferences(
      Long userId, UpdatePreferencesRequest request) {
    // Callable during and after onboarding ("온보딩 진행 중과 완료 후 모두 호출할 수 있다") - no
    // PENDING_ONBOARDING gate, unlike e.g. HobbyService.list.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);

    if (request.optionsVersion() == null
        || request.budgetLevel() == null
        || request.availableTimeMinutes() == null
        || request.indoorOutdoor() == null
        || request.difficultyPreference() == null
        || request.preferredCategoryIds() == null
        || request.expectedVersion() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    List<Integer> categoryIds = request.preferredCategoryIds();
    Set<Integer> distinctCategoryIds = new HashSet<>(categoryIds);
    if (!BUDGET_LEVELS.contains(request.budgetLevel())
        || !AVAILABLE_TIME_MINUTES.contains(request.availableTimeMinutes())
        || !INDOOR_OUTDOOR_VALUES.contains(request.indoorOutdoor())
        || !DIFFICULTY_PREFERENCES.contains(request.difficultyPreference())
        || categoryIds.size() > MAX_PREFERRED_CATEGORIES
        || distinctCategoryIds.size() != categoryIds.size()
        // Reject a null element before containsAll below: BY_ID is an immutable Map.of(...)
        // map, and its keySet().contains(null)/containsAll(...) throws NullPointerException
        // instead of just returning false, which would otherwise surface as a 500 here.
        || categoryIds.contains(null)
        || !HobbyCategoryCatalog.BY_ID.keySet().containsAll(distinctCategoryIds))
      throw new ProjectException(PreferenceErrorCode.INVALID_SELECTION);

    if (!OnboardingService.OPTIONS_VERSION.equals(request.optionsVersion()))
      throw new ProjectException(PreferenceErrorCode.OPTIONS_VERSION_CHANGED);

    // The parent row also serializes creation when no preference row exists yet, same as
    // updateNotificationSettings above.
    UserPreference preference =
        preferences.findByUser_UserId(userId).orElseGet(() -> UserPreference.forUser(user));
    if (preference.getPreferencesVersion() != request.expectedVersion())
      throw new ProjectException(PreferenceErrorCode.VERSION_CONFLICT);

    boolean unchanged =
        Objects.equals(preference.getOptionsVersion(), request.optionsVersion())
            && Objects.equals(preference.getBudgetLevel(), request.budgetLevel())
            && Objects.equals(preference.getAvailableTimeMinutes(), request.availableTimeMinutes())
            && Objects.equals(preference.getIndoorOutdoor(), request.indoorOutdoor())
            && Objects.equals(preference.getDifficultyPreference(), request.difficultyPreference())
            && distinctCategoryIds.equals(
                new HashSet<>(parseCategoryIds(preference.getPreferredCategoryIds())));

    preference.replacePreferences(
        request.optionsVersion(),
        request.budgetLevel(),
        request.availableTimeMinutes(),
        request.indoorOutdoor(),
        request.difficultyPreference(),
        serializeCategoryIds(categoryIds));
    if (!unchanged) preference.incrementPreferencesVersion();
    // updatedAt is DB-generated (@UpdateTimestamp(source = SourceType.DB)); flush now so the
    // INSERT/UPDATE runs and Hibernate reads the fresh value back before the response is built.
    preferences.saveAndFlush(preference);

    return new UpdatePreferencesResponse(
        preference.getOptionsVersion(),
        preference.getBudgetLevel(),
        preference.getAvailableTimeMinutes(),
        preference.getIndoorOutdoor(),
        preference.getDifficultyPreference(),
        categoryIds,
        userId,
        preference.getPreferencesVersion(),
        preference.getUpdatedAt());
  }

  private String serializeCategoryIds(List<Integer> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
  }

  private List<Integer> parseCategoryIds(String json) {
    String trimmed = json.substring(1, json.length() - 1).strip();
    if (trimmed.isEmpty()) return List.of();
    return Arrays.stream(trimmed.split(",")).map(String::strip).map(Integer::parseInt).toList();
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
  public CompleteOnboardingResponse completeOnboarding(
      Long userId, CompleteOnboardingRequest request) {
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    // AuthFilter only ever admits ACTIVE/PENDING_ONBOARDING this far, matching this endpoint's
    // own precondition ("PENDING_ONBOARDING 또는 ACTIVE인지 확인").

    if (request.preferencesVersion() == null || request.agreements() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    for (AgreementRequest agreement : request.agreements())
      if (agreement == null
          || agreement.termsId() == null
          || agreement.version() == null
          || agreement.agreed() == null)
        throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    Set<Integer> submittedTermsIds =
        request.agreements().stream().map(AgreementRequest::termsId).collect(Collectors.toSet());
    // Malformed agreements shape (duplicate or unknown termsId) - not the same as a required
    // term being left unagreed, which ONBOARDING400_1 covers below.
    if (submittedTermsIds.size() != request.agreements().size()
        || !OnboardingService.TERMS_BY_ID.keySet().containsAll(submittedTermsIds))
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    // Step 2 of the spec's own order: preferencesVersion, then required profile, before terms.
    UserPreference preference =
        preferences
            .findByUser_UserId(userId)
            .filter(p -> p.getPreferencesVersion() != 0)
            .orElseThrow(() -> new ProjectException(OnboardingErrorCode.VERSION_CONFLICT));
    if (preference.getPreferencesVersion() != request.preferencesVersion())
      throw new ProjectException(OnboardingErrorCode.VERSION_CONFLICT);
    // "기본 닉네임이 유효하면 별도의 닉네임 수정은 필수가 아니다" - any non-blank nickname
    // (social-login default or API-06 update) satisfies the required-profile check.
    if (user.getNickname() == null || user.getNickname().isBlank())
      throw new ProjectException(OnboardingErrorCode.REQUIRED_AGREEMENT_OR_PROFILE_MISSING);

    // Step 3: each submitted agreement's version must match that term's current version,
    // and every required term must be present with agreed:true.
    for (AgreementRequest agreement : request.agreements()) {
      TermsResponse terms = OnboardingService.TERMS_BY_ID.get(agreement.termsId());
      if (!terms.version().equals(agreement.version()))
        throw new ProjectException(OnboardingErrorCode.VERSION_CONFLICT);
    }
    for (TermsResponse terms : OnboardingService.TERMS) {
      if (!terms.isRequired()) continue;
      boolean agreedToRequiredTerm =
          request.agreements().stream()
              .anyMatch(
                  a -> a.termsId().equals(terms.termsId()) && Boolean.TRUE.equals(a.agreed()));
      if (!agreedToRequiredTerm)
        throw new ProjectException(OnboardingErrorCode.REQUIRED_AGREEMENT_OR_PROFILE_MISSING);
    }

    for (AgreementRequest agreement : request.agreements())
      termsAgreements.save(
          UserTermsAgreement.of(
              userId, agreement.termsId(), agreement.version(), agreement.agreed()));

    // Step 4: only a PENDING_ONBOARDING user transitions and gets a completion time; an
    // already-ACTIVE user re-confirming the same (still-valid) input just gets back the
    // original completedAt, unchanged.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      user.completeOnboarding(clock.instant());
    // updatedAt/onboardingCompletedAt are plain fields set in Java above (not DB-generated),
    // so saveAndFlush isn't needed for them - just persist the new agreement rows and the
    // user's own pending change together via the existing transaction.
    users.flush();

    return new CompleteOnboardingResponse(
        user.getUserId(), user.getAccountStatus().name(), true, user.getOnboardingCompletedAt());
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
