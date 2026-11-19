package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.activity.repository.SavedActivityRepository;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityCursor;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityService;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Calls the service directly, without a security filter or authentication override. */
class SavedActivityServiceTests {
  private static final long USER_ID = 17L;
  private static final long ACTIVITY_ID = 103L;
  private final UserRepository users = mock(UserRepository.class);
  private final ActivityRepository activities = mock(ActivityRepository.class);
  private final SavedActivityRepository saved = mock(SavedActivityRepository.class);
  private final SavedActivityCursor cursors = mock(SavedActivityCursor.class);
  private final SavedActivityService service =
      new SavedActivityService(users, activities, saved, cursors);

  @ParameterizedTest
  @MethodSource("ineligibleMembers")
  void rejectsIneligibleMembersBeforeReadingOrChangingBookmarks(
      AccountStatus status, AuthErrorCode expected, Operation operation) {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(status);
    memberLookup(operation, Optional.of(user));
    assertRejected(operation, expected);
  }

  @ParameterizedTest
  @EnumSource(Operation.class)
  void rejectsMissingMembersBeforeReadingOrChangingBookmarks(Operation operation) {
    memberLookup(operation, Optional.empty());
    assertRejected(operation, AuthErrorCode.ACCESS_TOKEN);
  }

  private void memberLookup(Operation operation, Optional<User> user) {
    if (operation == Operation.LIST) when(users.findById(USER_ID)).thenReturn(user);
    else when(users.findForLogin(USER_ID)).thenReturn(user);
  }

  private void assertRejected(Operation operation, AuthErrorCode expected) {
    assertThatThrownBy(() -> invoke(operation))
        .isInstanceOfSatisfying(
            AuthException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    if (operation == Operation.LIST) verify(users).findById(USER_ID);
    else verify(users).findForLogin(USER_ID);
    verifyNoMoreInteractions(users);
    verifyNoInteractions(activities, saved, cursors);
  }

  private void invoke(Operation operation) {
    switch (operation) {
      case SAVE -> service.save(USER_ID, ACTIVITY_ID);
      case UNSAVE -> service.unsave(USER_ID, ACTIVITY_ID);
      case LIST -> service.listMine(USER_ID, null, null, null);
    }
  }

  private static Stream<Arguments> ineligibleMembers() {
    return Stream.of(
            new RejectedState(
                AccountStatus.PENDING_ONBOARDING, AuthErrorCode.ONBOARDING_INCOMPLETE),
            new RejectedState(AccountStatus.SUSPENDED, AuthErrorCode.SUSPENDED),
            new RejectedState(AccountStatus.WITHDRAWN, AuthErrorCode.WITHDRAWN_ACCESS),
            new RejectedState(AccountStatus.ANONYMIZED, AuthErrorCode.ACCESS_TOKEN),
            new RejectedState(null, AuthErrorCode.ACCESS_TOKEN))
        .flatMap(
            state ->
                Arrays.stream(Operation.values())
                    .map(operation -> Arguments.of(state.status(), state.error(), operation)));
  }

  private record RejectedState(AccountStatus status, AuthErrorCode error) {}

  private enum Operation {
    SAVE,
    UNSAVE,
    LIST
  }
}
