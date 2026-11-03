package com.hobbytree.hobbytree.domain.notification.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.notification.code.NotificationErrorCode;
import com.hobbytree.hobbytree.domain.notification.dto.ChatLaunchSubscriptionResponse;
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
  private final NotificationSubscriptionRepository subscriptions;
  private final Environment environment;

  public NotificationSubscriptionService(
      UserRepository users,
      NotificationSubscriptionRepository subscriptions,
      Environment environment) {
    this.users = users;
    this.subscriptions = subscriptions;
    this.environment = environment;
  }

  public record SubscriptionOutcome(ChatLaunchSubscriptionResponse body, boolean created) {}

  @Transactional
  public SubscriptionOutcome subscribeToChatLaunch(Long userId) {
    // Lock the user row (same as NotificationService.markRead) so two concurrent requests for
    // the same user serialize: the loser then sees the winner's already-committed row instead
    // of racing the uq_sub_chat partial unique index.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
    // Read live (not frozen at startup) so ops can close applications without a redeploy.
    if (environment.getProperty(
        "notification.notification-subscriptions.chat-launch.closed", Boolean.class, false))
      throw new ProjectException(NotificationErrorCode.CHAT_LAUNCH_CLOSED);

    var existing =
        subscriptions.findByUser_UserIdAndSubscriptionTypeAndUnsubscribedAtIsNull(
            userId, SubscriptionType.CHAT_LAUNCH);
    if (existing.isPresent()) return outcome(existing.get(), false);

    var saved = subscriptions.save(NotificationSubscription.chatLaunch(user));
    return outcome(saved, true);
  }

  private SubscriptionOutcome outcome(NotificationSubscription subscription, boolean created) {
    return new SubscriptionOutcome(
        new ChatLaunchSubscriptionResponse(
            "CHAT_LAUNCH", true, "IN_APP", subscription.getSubscribedAt()),
        created);
  }
}
