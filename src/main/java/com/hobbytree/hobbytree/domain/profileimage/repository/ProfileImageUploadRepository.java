package com.hobbytree.hobbytree.domain.profileimage.repository;

import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUpload;
import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileImageUploadRepository extends JpaRepository<ProfileImageUpload, Long> {
  Optional<ProfileImageUpload> findByUserIdAndObjectKeyAndStatus(
      Long userId, String objectKey, ProfileImageUploadStatus status);
}
