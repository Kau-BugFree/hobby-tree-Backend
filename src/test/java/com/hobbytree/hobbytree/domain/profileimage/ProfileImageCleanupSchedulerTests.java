package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import com.hobbytree.hobbytree.domain.profileimage.repository.ProfileImageUploadRepository;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageCleanupScheduler;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageCleanupService;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageStorageGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileImageCleanupSchedulerTests {
  private final ProfileImageUploadRepository uploads = mock(ProfileImageUploadRepository.class);
  private final ProfileImageCleanupService cleanupService = mock(ProfileImageCleanupService.class);
  private final ProfileImageStorageGateway storage = mock(ProfileImageStorageGateway.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-02T04:30:00Z"), ZoneOffset.UTC);
  private final ProfileImageCleanupScheduler scheduler =
      new ProfileImageCleanupScheduler(uploads, cleanupService, storage, clock);

  @Test
  void deletesTheSourceKeyAndConfirmsRowRemovalForEachClaimedExpiredUpload() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of(1L, 2L));
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of());
    when(cleanupService.findExpiredUnlinkedObjectKey(eq(1L), any()))
        .thenReturn(Optional.of("profiles/1/original.jpg"));
    when(cleanupService.findExpiredUnlinkedObjectKey(eq(2L), any())).thenReturn(Optional.empty());

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/1/original.jpg");
    verifyNoMoreInteractions(storage);
    verify(cleanupService).confirmExpiredUnlinkedUploadDeleted(eq(1L), any());
    verify(cleanupService, never()).confirmExpiredUnlinkedUploadDeleted(eq(2L), any());
  }

  // Review: "Retain cleanup records until S3 deletion succeeds" - if the delete call throws,
  // the row must not be removed, or a later sweep would have nothing left to retry against.
  @Test
  void neverConfirmsRowRemovalWhenTheExpiredUploadsDeleteThrows() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of(1L));
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of());
    when(cleanupService.findExpiredUnlinkedObjectKey(eq(1L), any()))
        .thenReturn(Optional.of("profiles/1/original.jpg"));
    doThrow(new RuntimeException("s3 unavailable"))
        .when(storage)
        .deleteObject("profiles/1/original.jpg");

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(cleanupService, never()).confirmExpiredUnlinkedUploadDeleted(any(), any());
  }

  @Test
  void deletesBothKeysAndConfirmsRowRemovalForASupersededLinkedUpload() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of());
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L, 6L));
    when(cleanupService.findLinkedCleanupTargets(5L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/5/original.jpg", "profiles/5/final/old.jpg")));
    when(cleanupService.findLinkedCleanupTargets(6L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/6/original.jpg", null)));

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/5/original.jpg");
    verify(storage).deleteObject("profiles/5/final/old.jpg");
    verify(storage).deleteObject("profiles/6/original.jpg");
    verifyNoMoreInteractions(storage);
    verify(cleanupService).confirmSupersededLinkedUploadDeleted(eq(5L), any());
    // Row 6 is still current (no supersededFinalKey) - never eligible for row removal.
    verify(cleanupService, never()).confirmSupersededLinkedUploadDeleted(eq(6L), any());
  }

  // Review: "Retain cleanup records until S3 deletion succeeds" - even a failure of the
  // source-key delete (attempted first) must stop the final-key delete from being attempted
  // and must never confirm row removal, so a later sweep retries both from scratch.
  @Test
  void neverAttemptsTheFinalKeyDeleteOrConfirmsRowRemovalWhenTheSourceKeyDeleteThrows() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of());
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L));
    when(cleanupService.findLinkedCleanupTargets(5L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/5/original.jpg", "profiles/5/final/old.jpg")));
    doThrow(new RuntimeException("s3 unavailable"))
        .when(storage)
        .deleteObject("profiles/5/original.jpg");

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage, never()).deleteObject("profiles/5/final/old.jpg");
    verify(cleanupService, never()).confirmSupersededLinkedUploadDeleted(any(), any());
  }

  // Even when the source-key delete succeeds, a final-key delete failure must still stop row
  // removal - both deletions, not just the first, are required before the row can go.
  @Test
  void neverConfirmsRowRemovalWhenOnlyTheFinalKeyDeleteThrows() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of());
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L));
    when(cleanupService.findLinkedCleanupTargets(5L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/5/original.jpg", "profiles/5/final/old.jpg")));
    doThrow(new RuntimeException("s3 unavailable"))
        .when(storage)
        .deleteObject("profiles/5/final/old.jpg");

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/5/original.jpg");
    verify(cleanupService, never()).confirmSupersededLinkedUploadDeleted(any(), any());
  }

  @Test
  void aFailingCandidateInEitherScanDoesNotStopTheRestOfTheSweep() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of(1L, 2L));
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L));
    when(cleanupService.findExpiredUnlinkedObjectKey(eq(1L), any()))
        .thenThrow(new RuntimeException("boom"));
    when(cleanupService.findExpiredUnlinkedObjectKey(eq(2L), any()))
        .thenReturn(Optional.of("profiles/2/original.jpg"));
    when(cleanupService.findLinkedCleanupTargets(5L)).thenThrow(new RuntimeException("boom"));

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/2/original.jpg");
    verifyNoMoreInteractions(storage);
  }
}
