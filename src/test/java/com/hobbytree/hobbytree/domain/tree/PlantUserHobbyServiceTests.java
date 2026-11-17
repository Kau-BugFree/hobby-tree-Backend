package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationItemRepository;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationRepository;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.dto.PlantUserHobbyRequest;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.tree.service.UserHobbyService;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

class PlantUserHobbyServiceTests {
  private final UserHobbyRepository trees = mock(UserHobbyRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final HobbyRepository hobbies = mock(HobbyRepository.class);
  private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
  private final RecommendationItemRepository items = mock(RecommendationItemRepository.class);
  private final UserHobbyService service =
      new UserHobbyService(
          trees,
          users,
          mock(HobbyStageRepository.class),
          mock(ActivityRecordRepository.class),
          hobbies,
          recommendations,
          items);
  private final PlantUserHobbyRequest request = new PlantUserHobbyRequest(10L, null);

  @Test
  void factoryPreservesTheStageZeroActiveDefaults() {
    var user = mock(User.class);
    var hobby = mock(Hobby.class);
    var tree = UserHobby.plant(user, hobby);

    assertThat(tree.getUser()).isSameAs(user);
    assertThat(tree.getHobby()).isSameAs(hobby);
    assertThat(tree.getTreeStatus()).isEqualTo(TreeStatus.ACTIVE);
    assertThat(tree.getCurrentStageNumber()).isZero();
    assertThat(tree.getPausedAt()).isNull();
    assertThat(tree.getEndedAt()).isNull();
  }

  @Test
  void rejectsMissingPrincipalBeforeLocking() {
    assertThatThrownBy(() -> service.plant(null, request))
        .isInstanceOfSatisfying(
            AuthException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    verifyNoInteractions(users, hobbies, trees, recommendations, items);
  }

  @ParameterizedTest
  @CsvSource({
    "PENDING_ONBOARDING, ONBOARDING_INCOMPLETE", "SUSPENDED, SUSPENDED",
    "WITHDRAWN, WITHDRAWN_ACCESS", "ANONYMIZED, ACCESS_TOKEN"
  })
  void checksTheLockedAccountStatusBeforeUsingOtherRepositories(
      AccountStatus status, AuthErrorCode expected) {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(status);
    when(users.findForLogin(1L)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.plant(1L, request))
        .isInstanceOfSatisfying(
            AuthException.class, error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    verifyNoInteractions(hobbies, trees, recommendations, items);
  }

  @Test
  void locksTheUserBeforeRecheckingDuplicatesAndSlots() {
    activeUserAndHobby();
    when(trees.existsByUser_UserIdAndHobby_HobbyId(1L, 10L)).thenReturn(true);

    assertThatThrownBy(() -> service.plant(1L, request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(TreeErrorCode.ALREADY_PLANTED));
    var order = inOrder(users, hobbies, trees);
    order.verify(users).findForLogin(1L);
    order.verify(hobbies).findForPlanting(10L);
    order.verify(trees).existsByUser_UserIdAndHobby_HobbyId(1L, 10L);
    verify(trees, never()).saveAndFlush(any());
  }

  @Test
  void leavesUnrelatedIntegrityViolationsAsServerErrors() {
    activeUserAndHobby();
    var error =
        new DataIntegrityViolationException(
            "sequence collision",
            new ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), "user_hobbies_pkey"));
    when(trees.saveAndFlush(any())).thenThrow(error);

    assertThatThrownBy(() -> service.plant(1L, request)).isSameAs(error);
    verifyNoInteractions(recommendations, items);
  }

  private void activeUserAndHobby() {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    when(users.findForLogin(1L)).thenReturn(Optional.of(user));
    when(hobbies.findForPlanting(10L)).thenReturn(Optional.of(mock(Hobby.class)));
  }
}
