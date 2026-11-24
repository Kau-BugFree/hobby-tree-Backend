package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUpload;
import com.hobbytree.hobbytree.domain.profileimage.repository.ProfileImageUploadRepository;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageCleanupService;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Issue #277: plain Mockito tests for the per-row read/confirm logic, since none of it needs a
// real DB round trip to check - only the transaction boundary around it does. find* methods
// must never delete a row themselves (reviewed: the row is the only durable record of what
// still needs an S3 delete, so removing it before that delete has actually succeeded would
// leave nothing for a later sweep to retry).
class ProfileImageCleanupServiceTests {
  private final ProfileImageUploadRepository uploads = mock(ProfileImageUploadRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ProfileImageCleanupService service = new ProfileImageCleanupService(uploads, users);

  private ProfileImageUpload issued(Instant expiresAt) {
    return ProfileImageUpload.issue(1L, "profiles/1/original.jpg", "image/jpeg", 1024, expiresAt);
  }

  @Test
  void findReturnsTheKeyOfAnExpiredNeverLinkedUploadWithoutDeletingTheRow() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    var result = service.findExpiredUnlinkedObjectKey(10L, Instant.parse("2026-01-02T00:00:00Z"));

    assertThat(result).contains("profiles/1/original.jpg");
    verify(uploads, never()).delete(any());
  }

  @Test
  void confirmDeletesTheRowWhenStillEligible() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    service.confirmExpiredUnlinkedUploadDeleted(10L, Instant.parse("2026-01-02T00:00:00Z"));

    verify(uploads).delete(upload);
  }

  @Test
  void confirmLeavesTheRowAloneIfItHasSinceBeenLinked() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/current.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    service.confirmExpiredUnlinkedUploadDeleted(10L, Instant.parse("2026-01-02T00:00:00Z"));

    verify(uploads, never()).delete(any());
  }

  @Test
  void findLinkedTargetsNeverDeletesTheRowEvenWhenSuperseded() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/old.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/new.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    var result = service.findLinkedCleanupTargets(10L);

    assertThat(result).isPresent();
    assertThat(result.get().objectKey()).isEqualTo("profiles/1/original.jpg");
    assertThat(result.get().supersededFinalKey()).isEqualTo("profiles/1/final/old.jpg");
    verify(uploads, never()).delete(any());
  }

  @Test
  void findLinkedTargetsReturnsNoSupersededKeyWhenStillCurrent() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/current.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/current.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    var result = service.findLinkedCleanupTargets(10L);

    assertThat(result).isPresent();
    assertThat(result.get().objectKey()).isEqualTo("profiles/1/original.jpg");
    assertThat(result.get().supersededFinalKey()).isNull();
  }

  @Test
  void findLinkedTargetsLeavesALegacyRowWithNoRecordedFinalKeyAlone() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked(null);
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    var result = service.findLinkedCleanupTargets(10L);

    assertThat(result).isPresent();
    assertThat(result.get().supersededFinalKey()).isNull();
    verify(users, never()).findById(any());
  }

  // Review: "Wait for upload URL expiry before retiring linked cleanup records" - without this
  // guard, a client could still PUT new content to objectKey with its still-valid presigned URL
  // after the row (the only record able to rediscover it) is removed.
  @Test
  void confirmSupersededLeavesTheRowAloneWhileItsUploadUrlCouldStillBeValid() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/old.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/new.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    // cutoff is before expiresAt (2026-01-01), so the upload URL has not expired yet.
    service.confirmSupersededLinkedUploadDeleted(10L, Instant.parse("2025-12-31T00:00:00Z"));

    verify(uploads, never()).delete(any());
  }

  @Test
  void confirmSupersededDeletesTheRowOnceTheUploadUrlHasExpired() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/old.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/new.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    service.confirmSupersededLinkedUploadDeleted(10L, Instant.parse("2026-01-02T00:00:00Z"));

    verify(uploads).delete(upload);
  }

  @Test
  void confirmSupersededLeavesTheRowAloneIfNoLongerSuperseded() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/current.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/current.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    service.confirmSupersededLinkedUploadDeleted(10L, Instant.parse("2026-01-02T00:00:00Z"));

    verify(uploads, never()).delete(any());
  }
}
