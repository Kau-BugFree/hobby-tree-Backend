package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.coach.client.CoachTranscriptionClient;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachTranscriptionResponse;
import com.hobbytree.hobbytree.domain.coach.service.CoachTranscriptionService;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.*;
import com.hobbytree.hobbytree.global.ratelimit.RateLimiter;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;

class CoachTranscriptionServiceTests {
  UserRepository users = mock(UserRepository.class);
  CoachTranscriptionClient ai = mock(CoachTranscriptionClient.class);
  RateLimiter rates = mock(RateLimiter.class);
  CoachTranscriptionService service = new CoachTranscriptionService(users, ai, rates);
  User user = mock(User.class);

  @BeforeEach
  void setup() {
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    when(users.findById(anyLong())).thenReturn(Optional.of(user));
    when(ai.transcribe(any(), any())).thenReturn(new CoachTranscriptionResponse("인식 결과"));
  }

  MockMultipartFile file(String type, byte[] bytes) {
    return new MockMultipartFile("file", "voice.m4a", type, bytes);
  }

  void error(CoachErrorCode code, MockMultipartFile file) {
    assertThatThrownBy(() -> service.transcribe(1L, file))
        .isInstanceOfSatisfying(
            ProjectException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  @Test
  void checksEveryInactiveStatusBeforeAiOrRateBudget() {
    for (AccountStatus status : AccountStatus.values()) {
      if (status == AccountStatus.ACTIVE) continue;
      when(user.getAccountStatus()).thenReturn(status);
      assertThatThrownBy(() -> service.transcribe(1L, file("audio/mp4", new byte[] {1})))
          .isInstanceOf(AuthException.class);
    }
    assertThatThrownBy(() -> service.transcribe(null, null)).isInstanceOf(AuthException.class);
    verifyNoInteractions(ai, rates);
  }

  @Test
  void acceptsExactLimitAndRejectsTooLargeEmptyAndForgedSize() {
    byte[] maximum = new byte[CoachTranscriptionService.MAX_AUDIO_BYTES];
    assertThat(service.transcribe(1L, file("Audio/MP4; codecs=mp4a.40.2", maximum)).text())
        .isEqualTo("인식 결과");
    verify(rates).require("coach-stt", 1L, 3, Duration.ofMinutes(1));
    error(CoachErrorCode.STT_TOO_LARGE, file("audio/mp4", new byte[maximum.length + 1]));
    error(CoachErrorCode.STT_INVALID_AUDIO, file("audio/mp4", new byte[0]));
    error(
        CoachErrorCode.STT_TOO_LARGE,
        new MockMultipartFile("file", "v.m4a", "audio/mp4", new byte[maximum.length + 1]) {
          @Override
          public long getSize() {
            return 1;
          }
        });
  }

  @Test
  void allowsOnlyRecognizableM4aFallbackAndValidAudioMime() {
    byte[] m4a = new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
    service.transcribe(1L, file(null, m4a));
    service.transcribe(1L, file("application/octet-stream", m4a));
    verify(ai, times(2)).transcribe(m4a, "audio/mp4");
    for (String type : List.of("application/json", "audio/*", "audio/mp4\r\nX: y", "bad")) {
      error(CoachErrorCode.STT_UNSUPPORTED_MEDIA, file(type, new byte[] {1}));
    }
    error(CoachErrorCode.STT_UNSUPPORTED_MEDIA, file(null, new byte[] {1}));
  }

  @Test
  void rateFailurePreventsPaidCallAndExceptionsReleaseSlot() {
    doThrow(new RateLimitExceededException(30))
        .when(rates)
        .require(anyString(), any(), anyInt(), any());
    assertThatThrownBy(() -> service.transcribe(1L, file("audio/mp4", new byte[] {1})))
        .isInstanceOf(RateLimitExceededException.class);
    verify(ai, never()).transcribe(any(), any());
    reset(rates);
    when(ai.transcribe(any(), any()))
        .thenThrow(new ProjectException(CoachErrorCode.STT_UNAVAILABLE))
        .thenReturn(new CoachTranscriptionResponse("복구"));
    error(CoachErrorCode.STT_UNAVAILABLE, file("audio/mp4", new byte[] {1}));
    assertThat(service.transcribe(1L, file("audio/mp4", new byte[] {1})).text()).isEqualTo("복구");
  }

  @Test
  void boundsConcurrentCallsAndRejectsDuplicateUserWithoutWaiting() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    when(ai.transcribe(any(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("test timed out");
              return new CoachTranscriptionResponse("완료");
            });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(() -> service.transcribe(1L, file("audio/mp4", new byte[] {1})));
      var second = pool.submit(() -> service.transcribe(2L, file("audio/mp4", new byte[] {1})));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.transcribe(1L, file("audio/mp4", new byte[] {1})))
            .isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> service.transcribe(3L, file("audio/mp4", new byte[] {1})))
            .isInstanceOf(RateLimitExceededException.class);
      } finally {
        release.countDown();
      }
      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);
    }
    assertThat(service.transcribe(3L, file("audio/mp4", new byte[] {1})).text()).isEqualTo("완료");
  }
}
