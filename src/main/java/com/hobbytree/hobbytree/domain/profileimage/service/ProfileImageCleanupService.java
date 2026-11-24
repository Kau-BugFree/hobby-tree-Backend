package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUpload;
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

// Issue #277: every method below is its own short, DB-only transaction - no S3 call ever runs
// while a row is locked, the same "prepare / external call" shape #185/#269/#274 use. The
// caller (ProfileImageCleanupScheduler) makes the actual S3 deleteObject calls in between, with
// no transaction open, and only calls a confirm*Deleted method - which is what actually removes
// the row - once every required S3 delete for that row has already succeeded. A row is each
// sweep's only durable record of what still needs deleting, so removing it any earlier would
// mean a failed or interrupted delete leaves nothing for a later sweep to retry against.
@Service
@RequiredArgsConstructor
public class ProfileImageCleanupService {
  private final ProfileImageUploadRepository uploads;
  private final UserRepository users;

  // An ISSUED upload whose presigned URL expired without ever being linked - genuinely
  // abandoned. Re-checks status and expiry under lock on every call (both this and the confirm
  // step below): the scheduler's own candidate scan is an unlocked read taken moments earlier,
  // and a legitimate finishLink() could otherwise race it.
  @Transactional
  public Optional<String> findExpiredUnlinkedObjectKey(Long uploadId, Instant cutoff) {
    return findEligibleExpiredUnlinked(uploadId, cutoff).map(ProfileImageUpload::getObjectKey);
  }

  @Transactional
  public void confirmExpiredUnlinkedUploadDeleted(Long uploadId, Instant cutoff) {
    findEligibleExpiredUnlinked(uploadId, cutoff).ifPresent(uploads::delete);
  }

  private Optional<ProfileImageUpload> findEligibleExpiredUnlinked(Long uploadId, Instant cutoff) {
    return uploads
        .findForUpdate(uploadId)
        .filter(u -> u.getStatus() == ProfileImageUploadStatus.ISSUED)
        .filter(u -> u.getExpiresAt().isBefore(cutoff));
  }

  // objectKey (the pre-copy source) is redundant the moment a row is LINKED, regardless of
  // whether it's still the user's current image - always returned, and safe to delete
  // immediately even before expiry: a stale presigned PUT URL recreating it is caught by the
  // next sweep as long as the row itself is still here. supersededFinalKey is non-null only
  // when this copy is no longer the user's current image; a row with no recorded finalKey
  // predates issue #277 and is never treated as superseded (see V33's migration comment).
  public record LinkedCleanupTargets(String objectKey, String supersededFinalKey) {}

  @Transactional
  public Optional<LinkedCleanupTargets> findLinkedCleanupTargets(Long uploadId) {
    return uploads
        .findForUpdate(uploadId)
        .filter(u -> u.getStatus() == ProfileImageUploadStatus.LINKED)
        .map(
            u ->
                new LinkedCleanupTargets(
                    u.getObjectKey(), isSuperseded(u) ? u.getFinalKey() : null));
  }

  // Only removes the row once it's still superseded (re-checked, since time has passed since
  // findLinkedCleanupTargets ran its own S3 calls) AND objectKey's presigned PUT URL - plus the
  // same grace period the ISSUED sweep uses - has actually expired. Without that second guard,
  // a client could still PUT new content to objectKey using that still-valid URL after this row
  // (the only record able to rediscover and re-clean it) is gone, leaving a permanent orphan
  // even though every deletion up to that point succeeded.
  @Transactional
  public void confirmSupersededLinkedUploadDeleted(Long uploadId, Instant urlExpiryCutoff) {
    uploads
        .findForUpdate(uploadId)
        .filter(u -> u.getStatus() == ProfileImageUploadStatus.LINKED)
        .filter(u -> u.getExpiresAt().isBefore(urlExpiryCutoff))
        .filter(this::isSuperseded)
        .ifPresent(uploads::delete);
  }

  private boolean isSuperseded(ProfileImageUpload upload) {
    String finalKey = upload.getFinalKey();
    if (finalKey == null) return false;
    String currentKey =
        users.findById(upload.getUserId()).map(User::getProfileImageKey).orElse(null);
    return !Objects.equals(finalKey, currentKey);
  }
}
