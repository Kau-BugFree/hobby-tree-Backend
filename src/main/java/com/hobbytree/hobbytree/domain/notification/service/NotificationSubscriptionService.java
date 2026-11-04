package com.hobbytree.hobbytree.domain.notification.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.experience.code.ExperienceErrorCode;
import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceType;
import com.hobbytree.hobbytree.domain.experience.entity.ListingStatus;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceRepository;
import com.hobbytree.hobbytree.domain.notification.code.NotificationErrorCode;
import com.hobbytree.hobbytree.domain.notification.dto.ChatLaunchSubscriptionResponse;
import com.hobbytree.hobbytree.domain.notification.dto.ClassOpenSubscriptionResponse;
import com.hobbytree.hobbytree.domain.notification.entity.NotificationSubscription;
import com.hobbytree.hobbytree.domain.notification.entity.SubscriptionType;
import com.hobbytree.hobbytree.domain.notification.repository.NotificationSubscriptionRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSubscriptionService {
  private final UserRepository users;
  private final ExperienceRepository experiences;
  private final NotificationSubscriptionRepository subscriptions;
  private final Environment environment;

  public NotificationSubscriptionService(
      UserRepository users,
      ExperienceRepository experiences,
      NotificationSubscriptionRepository subscriptions,
      Environment environment) {
    this.users = users;
    this.experiences = experiences;
    this.subscriptions = subscriptions;
    this.environment = environment;
  }

  public record SubscriptionOutcome<T>(T body, boolean created) {}

  @Transactional
  public SubscriptionOutcome<ChatLaunchSubscriptionResponse> subscribeToChatLaunch(Long userId) {
    // Lock the user row (same as NotificationService.markRead) so two concurrent requests for
    // the same user serialize: the loser then sees the winner's already-committed row instead
    // of racing the uq_sub_chat partial unique index.
    User user = lockActiveOnboardedUser(userId);
    // Read live (not frozen at startup) so ops can close applications without a redeploy.
    if (environment.getProperty(
        "notification.notification-subscriptions.chat-launch.closed", Boolean.class, false))
      throw new ProjectException(NotificationErrorCode.CHAT_LAUNCH_CLOSED);

    var existing =
        subscriptions.findByUser_UserIdAndSubscriptionTypeAndUnsubscribedAtIsNull(
            userId, SubscriptionType.CHAT_LAUNCH);
    if (existing.isPresent())
      return new SubscriptionOutcome<>(chatLaunchBody(existing.get()), false);

    var saved = subscriptions.save(NotificationSubscription.chatLaunch(user));
    return new SubscriptionOutcome<>(chatLaunchBody(saved), true);
  }

  @Transactional
  public SubscriptionOutcome<ClassOpenSubscriptionResponse> subscribeToClassOpen(
      Long userId, Long experienceId) {
    // Same user-row lock as subscribeToChatLaunch, so a concurrent repeat request for the
    // same (user, experience) serializes instead of racing the uq_sub_class partial index.
    User user = lockActiveOnboardedUser(userId);
    Experience experience =
        experiences
            .findById(experienceId)
            .orElseThrow(() -> new ProjectException(ExperienceErrorCode.NOT_FOUND));
    if (experience.getExperienceType() != ExperienceType.CLASS
        || experience.getListingStatus() != ListingStatus.COMING_SOON)
      throw new ProjectException(NotificationErrorCode.CLASS_NOT_OPEN_ELIGIBLE);

    var existing =
        subscriptions
            .findByUser_UserIdAndExperience_ExperienceIdAndSubscriptionTypeAndUnsubscribedAtIsNull(
                userId, experienceId, SubscriptionType.CLASS_OPEN);
    if (existing.isPresent())
      return new SubscriptionOutcome<>(classOpenBody(existing.get()), false);

    var saved = subscriptions.save(NotificationSubscription.classOpen(user, experience));
    return new SubscriptionOutcome<>(classOpenBody(saved), true);
  }

  private User lockActiveOnboardedUser(Long userId) {
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

  private ChatLaunchSubscriptionResponse chatLaunchBody(NotificationSubscription subscription) {
    return new ChatLaunchSubscriptionResponse(
        "CHAT_LAUNCH", true, "IN_APP", subscription.getSubscribedAt());
  }

  private ClassOpenSubscriptionResponse classOpenBody(NotificationSubscription subscription) {
    return new ClassOpenSubscriptionResponse(
        subscription.getExperience().getExperienceId(),
        true,
        "IN_APP",
        subscription.getSubscribedAt());
  }
}
