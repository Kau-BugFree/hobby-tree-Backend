package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import com.hobbytree.hobbytree.domain.profileimage.repository.ProfileImageUploadRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Issue #277: cleans up S3 objects no longer referenced anywhere - an upload abandoned before
// it was ever linked, or a final image replaced/reset since. Runs after
// AccountAnonymizationScheduler's 04:00 sweep so a just-anonymized user's reset profileImageKey
// is already visible to the LINKED scan below.
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageCleanupScheduler {
  // Presigned PUT URLs live 5 minutes (ProfileImageService.UPLOAD_URL_TTL); this grace window
  // gives any in-flight prepare/S3-copy/finish request far more room than it should ever need
  // before its upload is treated as abandoned.
  private static final Duration EXPIRY_GRACE = Duration.ofHours(1);

  private final ProfileImageUploadRepository uploads;
  private final ProfileImageCleanupService cleanupService;
  private final ProfileImageStorageGateway storage;
  private final Clock clock;

  @Scheduled(cron = "0 30 4 * * *", zone = "UTC")
  public void cleanupOrphanedProfileImageObjects() {
    Instant cutoff = clock.instant().minus(EXPIRY_GRACE);
    for (Long id :
        uploads.findIdsByStatusAndExpiresAtBefore(ProfileImageUploadStatus.ISSUED, cutoff)) {
      try {
        cleanupService.claimExpiredUnlinkedUpload(id, cutoff).ifPresent(storage::deleteObject);
      } catch (RuntimeException e) {
        log.error("Failed to clean up expired profile image upload: uploadId={}", id, e);
      }
    }

    for (Long id : uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)) {
      try {
        cleanupService.claimLinkedUpload(id).ifPresent(this::deleteLinkedTargets);
      } catch (RuntimeException e) {
        log.error("Failed to clean up linked profile image upload: uploadId={}", id, e);
      }
    }
  }

  private void deleteLinkedTargets(ProfileImageCleanupService.LinkedCleanupTargets targets) {
    storage.deleteObject(targets.objectKey());
    if (targets.supersededFinalKey() != null) storage.deleteObject(targets.supersededFinalKey());
  }
}
