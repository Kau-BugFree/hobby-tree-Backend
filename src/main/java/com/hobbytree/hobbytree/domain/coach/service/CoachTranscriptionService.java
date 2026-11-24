package com.hobbytree.hobbytree.domain.coach.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.coach.client.CoachTranscriptionClient;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachTranscriptionResponse;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.global.exception.RateLimitExceededException;
import com.hobbytree.hobbytree.global.ratelimit.RateLimiter;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class CoachTranscriptionService {
  public static final int MAX_AUDIO_BYTES = 7 * 1024 * 1024;
  private final UserRepository users;
  private final CoachTranscriptionClient ai;
  private final RateLimiter rateLimiter;
  // Per application instance. The shared Redis budget remains effective across replicas.
  private final Semaphore slots = new Semaphore(2);
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  @Transactional(propagation = Propagation.NEVER)
  public CoachTranscriptionResponse transcribe(Long userId, MultipartFile file) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    CoachAccess.requireActive(
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN)));
    ai.requireEnabled();
    if (file == null || file.isEmpty())
      throw new ProjectException(CoachErrorCode.STT_INVALID_AUDIO);
    if (file.getSize() > MAX_AUDIO_BYTES) throw new ProjectException(CoachErrorCode.STT_TOO_LARGE);
    rateLimiter.require("coach-stt", userId, 3, Duration.ofMinutes(1));
    if (!inFlight.add(userId)) throw new RateLimitExceededException(1);
    boolean acquired = slots.tryAcquire();
    try {
      if (!acquired) throw new RateLimitExceededException(1);
      byte[] audio;
      try (var input = file.getInputStream()) {
        audio = input.readNBytes(MAX_AUDIO_BYTES + 1);
      } catch (IOException e) {
        throw new ProjectException(CoachErrorCode.STT_INVALID_AUDIO);
      }
      if (audio.length > MAX_AUDIO_BYTES) throw new ProjectException(CoachErrorCode.STT_TOO_LARGE);
      if (audio.length == 0) throw new ProjectException(CoachErrorCode.STT_INVALID_AUDIO);
      return ai.transcribe(audio, contentType(file, audio));
    } finally {
      if (acquired) slots.release();
      inFlight.remove(userId);
    }
  }

  private String contentType(MultipartFile file, byte[] audio) {
    String raw = file.getContentType();
    if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("application/octet-stream")) {
      String name = file.getOriginalFilename();
      // Only the known mobile m4a fallback; never turn arbitrary binary uploads into audio.
      if (name != null
          && name.toLowerCase(Locale.ROOT).endsWith(".m4a")
          && audio.length >= 12
          && audio[4] == 'f'
          && audio[5] == 't'
          && audio[6] == 'y'
          && audio[7] == 'p') return "audio/mp4";
      throw new ProjectException(CoachErrorCode.STT_UNSUPPORTED_MEDIA);
    }
    try {
      if (raw.length() > 200 || raw.chars().anyMatch(c -> c < 32 || c > 126))
        throw new IllegalArgumentException();
      MediaType parsed = MediaType.parseMediaType(raw);
      if (!parsed.getType().equalsIgnoreCase("audio") || parsed.isWildcardSubtype())
        throw new IllegalArgumentException();
      return parsed.toString();
    } catch (IllegalArgumentException invalid) {
      throw new ProjectException(CoachErrorCode.STT_UNSUPPORTED_MEDIA);
    }
  }
}
