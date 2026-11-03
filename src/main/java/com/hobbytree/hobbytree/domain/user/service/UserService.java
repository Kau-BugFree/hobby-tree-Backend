package com.hobbytree.hobbytree.domain.user.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
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

  public UserService(
      UserRepository users,
      UserHobbyRepository userHobbies,
      RedisAuthStore store,
      Clock clock,
      UserPreferenceRepository preferences) {
    this.users = users;
    this.userHobbies = userHobbies;
    this.store = store;
    this.clock = clock;
    this.preferences = preferences;
  }

  public MyProfileResponse getMyProfile(Long userId) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    long treeCount = userHobbies.countByUser_UserIdAndTreeStatusIn(userId, SLOT_OCCUPYING_STATUSES);
    return new MyProfileResponse(
        user.getUserId(),
        user.getNickname(),
        // No upload API (API-08/09) exists yet, so profileImageKey is always null today;
        // this will need real key-to-URL resolution once one does.
        user.getProfileImageKey(),
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
        user.getCreatedAt());
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
    // updatedAt is DB-generated (@UpdateTimestamp(source = SourceType.DB)); flush now so the
    // UPDATE runs and Hibernate reads the fresh value back before the response is built.
    users.flush();
    return new UpdateProfileResponse(
        user.getUserId(),
        user.getNickname(),
        user.getProfileImageKey(),
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
