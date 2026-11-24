package com.hobbytree.hobbytree.domain.profileimage.repository;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUpload;
import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileImageUploadRepository extends JpaRepository<ProfileImageUpload, Long> {
  Optional<ProfileImageUpload> findByUserIdAndObjectKeyAndStatus(
      Long userId, String objectKey, ProfileImageUploadStatus status);

  // Issue #277: unlocked batch scans for ProfileImageCleanupScheduler - matches
  // idx_profile_image_uploads_cleanup. Each candidate id is re-checked and locked individually
  // in ProfileImageCleanupService before anything is deleted.
  @Query(
      "select pu.profileImageUploadId from ProfileImageUpload pu "
          + "where pu.status = :status and pu.expiresAt < :cutoff")
  List<Long> findIdsByStatusAndExpiresAtBefore(
      @Param("status") ProfileImageUploadStatus status, @Param("cutoff") Instant cutoff);

  @Query("select pu.profileImageUploadId from ProfileImageUpload pu where pu.status = :status")
  List<Long> findIdsByStatus(@Param("status") ProfileImageUploadStatus status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select pu from ProfileImageUpload pu where pu.profileImageUploadId = :id")
  Optional<ProfileImageUpload> findForUpdate(@Param("id") Long id);
}
