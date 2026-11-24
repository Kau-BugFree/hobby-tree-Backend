package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import com.hobbytree.hobbytree.domain.profileimage.repository.ProfileImageUploadRepository;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Issue #277: each claim below is its own short, DB-only transaction - no S3 call ever runs
// while a row is locked, the same "prepare / external call" shape #185/#269/#274 use. The
// caller (ProfileImageCleanupScheduler) makes the actual S3 deleteObject calls afterward, with
// no transaction open.
@Service
@RequiredArgsConstructor
public class ProfileImageCleanupService {
  private final ProfileImageUploadRepository uploads;
  private final UserRepository users;

  // An ISSUED upload whose presigned URL expired without ever being linked - genuinely
  // abandoned. Re-checks status and expiry under lock: the scheduler's own candidate scan is an
  // unlocked read taken moments earlier, and a legitimate finishLink() could otherwise race it.
  @Transactional
  public Optional<String> claimExpiredUnlinkedUpload(Long uploadId, Instant cutoff) {
    return uploads
        .findForUpdate(uploadId)
        .filter(u -> u.getStatus() == ProfileImageUploadStatus.ISSUED)
        .filter(u -> u.getExpiresAt().isBefore(cutoff))
        .map(
            u -> {
              String objectKey = u.getObjectKey();
              uploads.delete(u);
              return objectKey;
            });
  }

  // objectKey (the pre-copy source) is redundant the moment a row is LINKED, regardless of
  // whether it's still the user's current image - always returned. finalKey and the row itself
  // are only safe to delete once no longer referenced by the user's current profileImageKey; a
  // row linked before issue #277 added finalKey has none recorded and is left alone (see V33's
  // migration comment) rather than guessed at.
  public record LinkedCleanupTargets(String objectKey, String supersededFinalKey) {}

  @Transactional
  public Optional<LinkedCleanupTargets> claimLinkedUpload(Long uploadId) {
    return uploads
        .findForUpdate(uploadId)
        .filter(u -> u.getStatus() == ProfileImageUploadStatus.LINKED)
        .map(
            u -> {
              String finalKey = u.getFinalKey();
              String currentKey =
                  finalKey == null
                      ? null
                      : users.findById(u.getUserId()).map(User::getProfileImageKey).orElse(null);
              boolean superseded = finalKey != null && !Objects.equals(finalKey, currentKey);
              if (superseded) uploads.delete(u);
              return new LinkedCleanupTargets(u.getObjectKey(), superseded ? finalKey : null);
            });
  }
}
