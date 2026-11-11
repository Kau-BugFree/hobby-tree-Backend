package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.coach.client.CoachAiClient;
import com.hobbytree.hobbytree.domain.coach.dto.CoachChatRequest;
import com.hobbytree.hobbytree.domain.coach.repository.CoachConversationRepository;
import com.hobbytree.hobbytree.domain.coach.repository.CoachMessageRepository;
import com.hobbytree.hobbytree.domain.coach.service.CoachService;
import com.hobbytree.hobbytree.domain.coach.service.CoachTurnTransactions;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CoachServiceTests {
  @Test
  void anonymizedAccountCannotReserveOrInvokeAiEvenWithoutAuthFilter() {
    var users = mock(UserRepository.class);
    var user = mock(User.class);
    when(users.findById(7L)).thenReturn(Optional.of(user));
    when(user.getAccountStatus()).thenReturn(AccountStatus.ANONYMIZED);
    var conversations = mock(CoachConversationRepository.class);
    var messages = mock(CoachMessageRepository.class);
    var keys = mock(IdempotencyKeyService.class);
    var turns = mock(CoachTurnTransactions.class);
    var ai = mock(CoachAiClient.class);
    var service =
        new CoachService(users, conversations, messages, keys, new ObjectMapper(), turns, ai);
    assertThatThrownBy(() -> service.chat(7L, "test-key", new CoachChatRequest(null, "질문", null)))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    verifyNoInteractions(keys, turns, ai, conversations, messages);
  }
}
