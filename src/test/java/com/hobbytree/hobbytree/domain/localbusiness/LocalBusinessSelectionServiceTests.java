package com.hobbytree.hobbytree.domain.localbusiness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceSessionRepository;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategory;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.localbusiness.dto.SelectLocalBusinessRequest;
import com.hobbytree.hobbytree.domain.localbusiness.service.LocalBusinessSelectionService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class LocalBusinessSelectionServiceTests {
  ExperienceRepository experiences = mock(ExperienceRepository.class);
  ExperienceSessionRepository sessions = mock(ExperienceSessionRepository.class);
  HobbyRepository hobbies = mock(HobbyRepository.class);
  Instant now = Instant.parse("2026-11-17T00:00:00Z");
  Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  LocalBusinessSelectionService service =
      new LocalBusinessSelectionService(experiences, sessions, hobbies, clock);

  Hobby cookingHobby;
  SelectLocalBusinessRequest request =
      new SelectLocalBusinessRequest("COOKING", "맛있는 요리 교실", "교육>요리학원", "서울 강남구", "1", "2");

  @BeforeEach
  void setup() {
    cookingHobby = mock(Hobby.class);
    when(cookingHobby.getHobbyId()).thenReturn(7L);
    when(hobbies.findFirstByCategoryAndIsSupportedTrue(HobbyCategory.COOKING))
        .thenReturn(Optional.of(cookingHobby));
  }

  Experience experienceWithId(long id) {
    Experience experience =
        Experience.fromExternalSource(
            "NAVER", "1:2", "맛있는 요리 교실", "교육>요리학원", "서울 강남구", cookingHobby);
    ReflectionTestUtils.setField(experience, "experienceId", id);
    return experience;
  }

  ExperienceSession sessionWithId(long id, long experienceId) {
    ExperienceSession session =
        ExperienceSession.startingAt(experienceId, now.plusSeconds(3600), 60);
    ReflectionTestUtils.setField(session, "sessionId", id);
    return session;
  }

  @Test
  void createsANewExperienceAndSessionWhenTheBusinessIsNotKnownYet() {
    when(experiences.findByExternalSourceAndExternalRef("NAVER", "1:2"))
        .thenReturn(Optional.empty());
    Experience created = experienceWithId(10L);
    when(experiences.saveAndFlush(any())).thenReturn(created);
    when(sessions.findUpcomingByExperienceIds(List.of(10L), now)).thenReturn(List.of());
    ExperienceSession newSession = sessionWithId(100L, 10L);
    when(sessions.save(any())).thenReturn(newSession);

    var response = service.select(request);

    assertThat(response.experienceId()).isEqualTo(10L);
    assertThat(response.sessionId()).isEqualTo(100L);
    assertThat(response.isNew()).isTrue();
    verify(experiences).saveAndFlush(any());
  }

  @Test
  void reusesAnExistingExperienceAndItsUpcomingSession() {
    Experience existing = experienceWithId(10L);
    when(experiences.findByExternalSourceAndExternalRef("NAVER", "1:2"))
        .thenReturn(Optional.of(existing));
    ExperienceSession upcoming = sessionWithId(100L, 10L);
    when(sessions.findUpcomingByExperienceIds(List.of(10L), now)).thenReturn(List.of(upcoming));

    var response = service.select(request);

    assertThat(response.experienceId()).isEqualTo(10L);
    assertThat(response.sessionId()).isEqualTo(100L);
    assertThat(response.isNew()).isFalse();
    verify(experiences, never()).saveAndFlush(any());
    verify(sessions, never()).save(any());
  }

  @Test
  void createsAFreshSessionForAnExistingExperienceWithNoUpcomingOne() {
    Experience existing = experienceWithId(10L);
    when(experiences.findByExternalSourceAndExternalRef("NAVER", "1:2"))
        .thenReturn(Optional.of(existing));
    when(sessions.findUpcomingByExperienceIds(List.of(10L), now)).thenReturn(List.of());
    ExperienceSession newSession = sessionWithId(101L, 10L);
    when(sessions.save(any())).thenReturn(newSession);

    var response = service.select(request);

    assertThat(response.sessionId()).isEqualTo(101L);
    assertThat(response.isNew()).isFalse();
    verify(experiences, never()).saveAndFlush(any());
  }

  @Test
  void reusesTheWinnerWhenTwoConcurrentSelectsRaceOnTheSameBusiness() {
    when(experiences.findByExternalSourceAndExternalRef("NAVER", "1:2"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(experienceWithId(10L)));
    when(experiences.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(sessions.findUpcomingByExperienceIds(List.of(10L), now)).thenReturn(List.of());
    when(sessions.save(any())).thenReturn(sessionWithId(100L, 10L));

    var response = service.select(request);

    assertThat(response.experienceId()).isEqualTo(10L);
    assertThat(response.isNew()).isFalse();
  }

  @Test
  void rejectsAnUnconfiguredHobbyCategoryBeforeTouchingExperiences() {
    when(hobbies.findFirstByCategoryAndIsSupportedTrue(HobbyCategory.COOKING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.select(request)).isInstanceOf(ProjectException.class);
    verifyNoInteractions(experiences, sessions);
  }

  @Test
  void rejectsAnUnknownHobbyKeyword() {
    var invalid =
        new SelectLocalBusinessRequest("UNKNOWN", "맛있는 요리 교실", "교육>요리학원", "서울 강남구", "1", "2");
    assertThatThrownBy(() -> service.select(invalid)).isInstanceOf(ProjectException.class);
    verifyNoInteractions(experiences, sessions, hobbies);
  }
}
