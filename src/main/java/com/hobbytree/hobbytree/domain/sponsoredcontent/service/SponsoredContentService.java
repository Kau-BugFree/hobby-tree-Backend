package com.hobbytree.hobbytree.domain.sponsoredcontent.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.sponsoredcontent.dto.ActivitySponsoredContentResponse;
import com.hobbytree.hobbytree.domain.sponsoredcontent.dto.SponsoredContentItemResponse;
import com.hobbytree.hobbytree.domain.sponsoredcontent.entity.ActivitySponsoredContent;
import com.hobbytree.hobbytree.domain.sponsoredcontent.entity.SponsoredContentStatus;
import com.hobbytree.hobbytree.domain.sponsoredcontent.repository.ActivitySponsoredContentRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SponsoredContentService {
  private final UserRepository users;
  private final ActivityRepository activities;
  private final ActivitySponsoredContentRepository mappings;
  private final Clock clock;

  public SponsoredContentService(
      UserRepository users,
      ActivityRepository activities,
      ActivitySponsoredContentRepository mappings,
      Clock clock) {
    this.users = users;
    this.activities = activities;
    this.mappings = mappings;
    this.clock = clock;
  }

  // open-in-view is disabled in the ec2 profile, so without a transaction here the
  // ActivitySponsoredContent -> SponsoredContent lazy proxy would fail to initialize once
  // the repository call returns and the session closes (LazyInitializationException).
  @Transactional(readOnly = true)
  public ActivitySponsoredContentResponse findForActivity(Long userId, Long activityId) {
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Separate from AuthFilter's shared status gate, which still admits PENDING_ONBOARDING.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    activities
        .findById(activityId)
        .filter(a -> a.getReviewStatus() == ReviewStatus.PUBLISHED)
        .orElseThrow(() -> new ProjectException(ActivityErrorCode.NOT_SAVABLE));

    var rows =
        mappings.findVisibleByActivityId(
            activityId, SponsoredContentStatus.ACTIVE, clock.instant());
    return new ActivitySponsoredContentResponse(
        activityId, rows.stream().map(this::toItem).toList());
  }

  private SponsoredContentItemResponse toItem(ActivitySponsoredContent mapping) {
    var content = mapping.getSponsoredContent();
    return new SponsoredContentItemResponse(
        content.getSponsoredContentId(),
        content.getTitle(),
        content.getDescription(),
        content.getSponsorName(),
        content.getThumbnailUrl(),
        content.getContentType().name(),
        true,
        content.getDisclosureLabel(),
        content.getExternalUrl(),
        mapping.getSortOrder());
  }
}
