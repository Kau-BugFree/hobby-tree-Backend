package com.hobbytree.hobbytree.domain.localbusiness.service;

import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceSessionRepository;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategory;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.localbusiness.code.LocalBusinessErrorCode;
import com.hobbytree.hobbytree.domain.localbusiness.dto.SelectLocalBusinessRequest;
import com.hobbytree.hobbytree.domain.localbusiness.dto.SelectLocalBusinessResponse;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Issue #191: bridges a chosen local-search result into the existing experience/reservation
// system (API-37) by finding-or-creating one Experience + one bookable ExperienceSession for
// it, so the frontend can then call the unchanged reservation-creation endpoint with the
// returned ids.
@Service
@RequiredArgsConstructor
public class LocalBusinessSelectionService {
  private static final String EXTERNAL_SOURCE = "NAVER";
  private static final long SESSION_START_OFFSET_SECONDS = 2 * 3600L;
  private static final int SESSION_DURATION_MINUTES = 60;

  private final ExperienceRepository experiences;
  private final ExperienceSessionRepository sessions;
  private final HobbyRepository hobbies;
  private final Clock clock;

  @Transactional
  public SelectLocalBusinessResponse select(SelectLocalBusinessRequest request) {
    HobbyCategory category = parseCategory(request.hobby());
    Hobby hobby =
        hobbies
            .findFirstByCategoryAndIsSupportedTrue(category)
            .orElseThrow(() -> new ProjectException(LocalBusinessErrorCode.HOBBY_NOT_CONFIGURED));

    String externalRef = request.mapX() + ":" + request.mapY();
    boolean isNew = false;
    Experience experience =
        experiences.findByExternalSourceAndExternalRef(EXTERNAL_SOURCE, externalRef).orElse(null);
    if (experience == null) {
      Experience candidate =
          Experience.fromExternalSource(
              EXTERNAL_SOURCE,
              externalRef,
              request.name(),
              request.category(),
              request.address(),
              hobby);
      try {
        experience = experiences.saveAndFlush(candidate);
        isNew = true;
      } catch (DataIntegrityViolationException e) {
        // Lost the race on (external_source, external_ref) to a concurrent select of the same
        // business - reuse the winner's row instead.
        experience =
            experiences
                .findByExternalSourceAndExternalRef(EXTERNAL_SOURCE, externalRef)
                .orElseThrow(() -> e);
      }
    }

    Long experienceId = experience.getExperienceId();
    Instant now = clock.instant();
    ExperienceSession session =
        sessions.findUpcomingByExperienceIds(List.of(experienceId), now).stream()
            .findFirst()
            .orElseGet(
                () ->
                    sessions.save(
                        ExperienceSession.startingAt(
                            experienceId,
                            now.plusSeconds(SESSION_START_OFFSET_SECONDS),
                            SESSION_DURATION_MINUTES)));

    return new SelectLocalBusinessResponse(experienceId, session.getSessionId(), isNew);
  }

  private HobbyCategory parseCategory(String hobby) {
    try {
      return HobbyCategory.valueOf(hobby);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }
}
