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

// Issue #277: plain Mockito tests for the per-row claim logic, since none of it needs a real DB
// round trip to check - only the transaction boundary around it does.
class ProfileImageCleanupServiceTests {
  private final ProfileImageUploadRepository uploads = mock(ProfileImageUploadRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ProfileImageCleanupService service = new ProfileImageCleanupService(uploads, users);

  private ProfileImageUpload issued(Instant expiresAt) {
    return ProfileImageUpload.issue(1L, "profiles/1/original.jpg", "image/jpeg", 1024, expiresAt);
  }

  @Test
  void claimsAndDeletesAnExpiredNeverLinkedUpload() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    var result = service.claimExpiredUnlinkedUpload(10L, Instant.parse("2026-01-02T00:00:00Z"));

    assertThat(result).contains("profiles/1/original.jpg");
    verify(uploads).delete(upload);
  }

  @Test
  void leavesAnIssuedUploadAloneIfItHasNotExpiredYet() {
    var upload = issued(Instant.parse("2026-01-03T00:00:00Z"));
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    var result = service.claimExpiredUnlinkedUpload(10L, Instant.parse("2026-01-02T00:00:00Z"));

    assertThat(result).isEmpty();
    verify(uploads, never()).delete(any());
  }

  @Test
  void leavesAnAlreadyLinkedUploadAloneWhenScannedAsIssuedCandidate() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/current.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));

    var result = service.claimExpiredUnlinkedUpload(10L, Instant.parse("2026-01-02T00:00:00Z"));

    assertThat(result).isEmpty();
    verify(uploads, never()).delete(any());
  }

  @Test
  void claimsAndDeletesALinkedUploadSupersededByANewerImage() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/old.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/new.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    var result = service.claimLinkedUpload(10L);

    assertThat(result).isPresent();
    assertThat(result.get().objectKey()).isEqualTo("profiles/1/original.jpg");
    assertThat(result.get().supersededFinalKey()).isEqualTo("profiles/1/final/old.jpg");
    verify(uploads).delete(upload);
  }

  @Test
  void keepsTheRowButStillReturnsTheSourceKeyWhenStillTheCurrentImage() {
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    upload.markLinked("profiles/1/final/current.jpg");
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    user.linkProfileImage("profiles/1/final/current.jpg");
    when(users.findById(1L)).thenReturn(Optional.of(user));

    var result = service.claimLinkedUpload(10L);

    assertThat(result).isPresent();
    assertThat(result.get().objectKey()).isEqualTo("profiles/1/original.jpg");
    assertThat(result.get().supersededFinalKey()).isNull();
    verify(uploads, never()).delete(any());
  }

  @Test
  void keepsARowWithNoRecordedFinalKeyAloneSinceItPredatesIssue277() {
    // markLinked() was never called with a finalKey before this feature shipped - the row stays
    // LINKED with finalKey null forever (see V33's migration comment). Still returns the
    // source key, since that cleanup is unconditional; never guesses whether it's superseded.
    var upload = issued(Instant.parse("2026-01-01T00:00:00Z"));
    when(uploads.findForUpdate(10L)).thenReturn(Optional.of(upload));
    // Promote straight to LINKED with no finalKey, bypassing markLinked's own signature.
    upload.markLinked(null);

    var result = service.claimLinkedUpload(10L);

    assertThat(result).isPresent();
    assertThat(result.get().objectKey()).isEqualTo("profiles/1/original.jpg");
    assertThat(result.get().supersededFinalKey()).isNull();
    verify(uploads, never()).delete(any());
    verify(users, never()).findById(any());
  }
}
