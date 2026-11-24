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
  void deletesTheSourceKeyForEachClaimedExpiredUpload() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of(1L, 2L));
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of());
    when(cleanupService.claimExpiredUnlinkedUpload(eq(1L), any()))
        .thenReturn(Optional.of("profiles/1/original.jpg"));
    when(cleanupService.claimExpiredUnlinkedUpload(eq(2L), any())).thenReturn(Optional.empty());

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/1/original.jpg");
    verifyNoMoreInteractions(storage);
  }

  @Test
  void deletesBothKeysForASupersededLinkedUploadButOnlySourceWhenStillCurrent() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of());
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L, 6L));
    when(cleanupService.claimLinkedUpload(5L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/5/original.jpg", "profiles/5/final/old.jpg")));
    when(cleanupService.claimLinkedUpload(6L))
        .thenReturn(
            Optional.of(
                new ProfileImageCleanupService.LinkedCleanupTargets(
                    "profiles/6/original.jpg", null)));

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/5/original.jpg");
    verify(storage).deleteObject("profiles/5/final/old.jpg");
    verify(storage).deleteObject("profiles/6/original.jpg");
    verifyNoMoreInteractions(storage);
  }

  @Test
  void aFailingCandidateInEitherScanDoesNotStopTheRestOfTheSweep() {
    when(uploads.findIdsByStatusAndExpiresAtBefore(eq(ProfileImageUploadStatus.ISSUED), any()))
        .thenReturn(List.of(1L, 2L));
    when(uploads.findIdsByStatus(ProfileImageUploadStatus.LINKED)).thenReturn(List.of(5L));
    when(cleanupService.claimExpiredUnlinkedUpload(eq(1L), any()))
        .thenThrow(new RuntimeException("boom"));
    when(cleanupService.claimExpiredUnlinkedUpload(eq(2L), any()))
        .thenReturn(Optional.of("profiles/2/original.jpg"));
    when(cleanupService.claimLinkedUpload(5L)).thenThrow(new RuntimeException("boom"));

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject("profiles/2/original.jpg");
    verifyNoMoreInteractions(storage);
  }
}
