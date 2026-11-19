package com.hobbytree.hobbytree.domain.hobby;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.hobby.service.HobbyService;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Direct service calls intentionally have no security filter or authentication test override. */
class HobbyStageServiceTests {
  private final UserRepository users = mock(UserRepository.class);
  private final HobbyRepository hobbies = mock(HobbyRepository.class);
  private final ActivityRepository activities = mock(ActivityRepository.class);
  private final UserHobbyRepository trees = mock(UserHobbyRepository.class);
  private final HobbyStageRepository stages = mock(HobbyStageRepository.class);
  private final ActivityMaterialRepository materials = mock(ActivityMaterialRepository.class);
  private final HobbyService service =
      new HobbyService(users, hobbies, activities, trees, stages, materials);

  @ParameterizedTest
  @CsvSource({
    "PENDING_ONBOARDING, ONBOARDING_INCOMPLETE",
    "SUSPENDED, SUSPENDED",
    "WITHDRAWN, WITHDRAWN_ACCESS",
    "ANONYMIZED, ACCESS_TOKEN"
  })
  void rejectsEveryNonActiveStateBeforeReadingTheCatalog(
      AccountStatus status, AuthErrorCode expected) {
    var user = mock(User.class);
    when(users.findById(11L)).thenReturn(Optional.of(user));
    when(user.getAccountStatus()).thenReturn(status);
    assertThatThrownBy(() -> service.stages(11L, 22L))
        .isInstanceOfSatisfying(
            AuthException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    verify(users).findById(11L);
    verifyNoInteractions(hobbies, activities, trees, stages, materials);
  }

  @Test
  void rejectsMissingPrincipalWithoutReadingAnyRepository() {
    assertThatThrownBy(() -> service.stages(null, 22L))
        .isInstanceOfSatisfying(
            AuthException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    verifyNoInteractions(users, hobbies, activities, trees, stages, materials);
  }

  @Test
  void rejectsUnknownMemberBeforeReadingTheCatalog() {
    when(users.findById(11L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.stages(11L, 22L))
        .isInstanceOfSatisfying(
            AuthException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    verifyNoInteractions(hobbies, activities, trees, stages, materials);
  }

  @Test
  void activeMemberCanReadPublicStagesWithoutAPlantedTree() {
    var user = mock(User.class);
    when(users.findById(11L)).thenReturn(Optional.of(user));
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    var hobby = mock(Hobby.class);
    when(hobbies.findById(22L)).thenReturn(Optional.of(hobby));
    when(hobby.getIsSupported()).thenReturn(true);
    when(hobby.getActivitiesPerStage()).thenReturn((short) 3);
    when(hobby.getHobbyId()).thenReturn(22L);
    when(hobby.getHobbyName()).thenReturn("TEST ONLY stages");
    when(stages.findStageSummaries(22L)).thenReturn(List.of());
    var result = service.stages(11L, 22L);
    assertThat(result.hobbyId()).isEqualTo(22L);
    assertThat(result.hobbyName()).isEqualTo("TEST ONLY stages");
    assertThat(result.stages()).isEmpty();
    verifyNoInteractions(activities, trees, materials);
  }
}
