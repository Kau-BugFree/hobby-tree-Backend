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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// Issue #191: bridges a chosen local-search result into the existing experience/reservation
// system (API-37) by finding-or-creating one Experience + one bookable ExperienceSession for
// it, so the frontend can then call the unchanged reservation-creation endpoint with the
// returned ids.
@Service
public class LocalBusinessSelectionService {
  private static final String EXTERNAL_SOURCE = "NAVER";
  private static final long SESSION_START_OFFSET_SECONDS = 2 * 3600L;
  private static final int SESSION_DURATION_MINUTES = 60;

  private final ExperienceRepository experiences;
  private final ExperienceSessionRepository sessions;
  private final HobbyRepository hobbies;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public LocalBusinessSelectionService(
      ExperienceRepository experiences,
      ExperienceSessionRepository sessions,
      HobbyRepository hobbies,
      Clock clock,
      PlatformTransactionManager manager) {
    this.experiences = experiences;
    this.sessions = sessions;
    this.hobbies = hobbies;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public SelectLocalBusinessResponse select(SelectLocalBusinessRequest request) {
    HobbyCategory category = parseCategory(request.hobby());
    Hobby hobby =
        hobbies
            .findFirstByCategoryAndIsSupportedTrue(category)
            .orElseThrow(() -> new ProjectException(LocalBusinessErrorCode.HOBBY_NOT_CONFIGURED));

    String externalRef = buildExternalRef(request, category);
    boolean isNew = false;
    Experience experience =
        experiences.findByExternalSourceAndExternalRef(EXTERNAL_SOURCE, externalRef).orElse(null);
    if (experience == null) {
      try {
        // Runs in its own REQUIRES_NEW transaction/connection, independent of the method's
        // caller - see the retry below for why that matters.
        experience =
            transaction.execute(
                status ->
                    experiences.saveAndFlush(
                        Experience.fromExternalSource(
                            EXTERNAL_SOURCE,
                            externalRef,
                            request.name(),
                            request.category(),
                            request.address(),
                            hobby)));
        isNew = true;
      } catch (DataIntegrityViolationException e) {
        // Lost the race on (external_source, external_ref) to a concurrent select of the same
        // business. The failed save above already rolled back in its own transaction, so this
        // retry runs in a fresh REQUIRES_NEW transaction/connection rather than reusing one
        // Postgres has aborted - reusing it here would reject every statement with "current
        // transaction is aborted" instead of returning the winner's row.
        experience =
            transaction.execute(
                status ->
                    experiences
                        .findByExternalSourceAndExternalRef(EXTERNAL_SOURCE, externalRef)
                        .orElseThrow(() -> e));
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

  // Scoped to (coordinates, normalized business name, hobby category) rather than coordinates
  // alone: two different businesses sharing a coordinate (e.g. a multi-tenant building) must
  // not collapse into the same row, and the same business found under two different hobby
  // searches gets one row per hobby instead of one row whose hobby silently goes stale for the
  // second search - Experience has only one hobby, and the existing reservation flow rejects a
  // userHobbyId whose hobby doesn't match it.
  private String buildExternalRef(SelectLocalBusinessRequest request, HobbyCategory category) {
    String normalizedName = request.name().strip().toLowerCase();
    String key = request.mapX() + "|" + request.mapY() + "|" + normalizedName + "|" + category;
    return sha256Hex(key);
  }

  private String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private HobbyCategory parseCategory(String hobby) {
    try {
      return HobbyCategory.valueOf(hobby);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }
}
