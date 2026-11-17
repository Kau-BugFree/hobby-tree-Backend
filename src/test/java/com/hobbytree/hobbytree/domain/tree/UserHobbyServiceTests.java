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
import com.hobbytree.hobbytree.domain.tree.dto.UserHobbyListResponse;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.tree.service.UserHobbyService;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class UserHobbyServiceTests {
  private final UserHobbyRepository userHobbies = mock(UserHobbyRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final HobbyStageRepository stages = mock(HobbyStageRepository.class);
  private final ActivityRecordRepository records = mock(ActivityRecordRepository.class);
  private final UserHobbyService service =
      new UserHobbyService(
          userHobbies,
          users,
          stages,
          records,
          mock(HobbyRepository.class),
          mock(RecommendationRepository.class),
          mock(RecommendationItemRepository.class));

  @Test
  void returnsEmptyListForPrincipalWithoutTrees() {
    activeUser(41L);
    when(userHobbies.findOwnedWithHobby(
            41L, false, TreeStatus.ACTIVE, false, Instant.EPOCH, 0L, PageRequest.of(0, 21)))
        .thenReturn(List.of());

    assertThat(service.list(41L, null, null, null))
        .isEqualTo(new UserHobbyListResponse(List.of(), null, false, 0, 5));
    verifyNoInteractions(stages, records);
  }

  @Test
  void mapsOnlyThePrincipalsPageAndLoadsMetadataInBatches() {
    activeUser(42L);
    var first = tree(12L, 20L);
    var second = tree(11L, 21L);
    var lookahead = tree(10L, 22L);
    when(userHobbies.findOwnedWithHobby(
            42L, false, TreeStatus.ACTIVE, false, Instant.EPOCH, 0L, PageRequest.of(0, 3)))
        .thenReturn(List.of(first, second, lookahead));

    var result = service.list(42L, null, null, "2");

    assertThat(result.items())
        .extracting(UserHobbyListResponse.Item::userHobbyId)
        .containsExactly(12L, 11L);
    assertThat(result.hasNext()).isTrue();
    assertThat(result.nextCursor()).isNotBlank();
    verify(stages).findStageNamesByHobbyIds(List.of(20L, 21L));
    verify(records).countGrowthByTreeIds(List.of(12L, 11L));
  }

  @Test
  void rejectsMissingPrincipalBeforeQuerying() {
    assertThatThrownBy(() -> service.list(null, null, null, null))
        .isInstanceOfSatisfying(
            AuthException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    verifyNoInteractions(users, userHobbies, stages, records);
  }

  private void activeUser(long id) {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    when(users.findById(id)).thenReturn(Optional.of(user));
  }

  private UserHobby tree(long id, long hobbyId) {
    var hobby = mock(Hobby.class);
    when(hobby.getHobbyId()).thenReturn(hobbyId);
    when(hobby.getActivitiesPerStage()).thenReturn((short) 3);
    var tree = mock(UserHobby.class);
    when(tree.getUserHobbyId()).thenReturn(id);
    when(tree.getHobby()).thenReturn(hobby);
    when(tree.getCurrentStageNumber()).thenReturn((short) 0);
    when(tree.getPlantedAt()).thenReturn(Instant.EPOCH);
    return tree;
  }
}
