package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.profileimage.code.UploadErrorCode;
import com.hobbytree.hobbytree.domain.profileimage.dto.IssueUploadUrlRequest;
import com.hobbytree.hobbytree.domain.profileimage.dto.IssueUploadUrlResponse;
import com.hobbytree.hobbytree.domain.profileimage.dto.ResetProfileImageResponse;
import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUpload;
import com.hobbytree.hobbytree.domain.profileimage.entity.ProfileImageUploadStatus;
import com.hobbytree.hobbytree.domain.profileimage.repository.ProfileImageUploadRepository;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageService {
  private static final long MAX_FILE_SIZE = 5_242_880L;
  private static final int MAX_FILE_NAME_LENGTH = 100;
  private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");
  private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final UserRepository users;
  private final ProfileImageUploadRepository uploads;
  private final ProfileImageStorageGateway storage;
  private final Clock clock;

  @Transactional
  public IssueUploadUrlResponse issueUploadUrl(Long userId, IssueUploadUrlRequest request) {
    String fileName = request.fileName();
    String contentType = request.contentType();
    Long fileSize = request.fileSize();

    if (fileName == null
        || fileName.isBlank()
        || fileName.length() > MAX_FILE_NAME_LENGTH
        || contentType == null
        || !ALLOWED_CONTENT_TYPES.contains(contentType))
      throw new ProjectException(UploadErrorCode.UNSUPPORTED_FILE);
    if (fileSize == null || fileSize < 1)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    if (fileSize > MAX_FILE_SIZE) throw new ProjectException(UploadErrorCode.FILE_TOO_LARGE);

    String objectKey =
        "profiles/%d/%s.%s"
            .formatted(userId, UUID.randomUUID(), EXTENSION_BY_CONTENT_TYPE.get(contentType));
    Instant expiresAt = clock.instant().plus(UPLOAD_URL_TTL);

    String uploadUrl;
    try {
      uploadUrl = storage.presignUpload(objectKey, contentType, UPLOAD_URL_TTL);
    } catch (SdkException e) {
      // Presigning a PUT URL is a local signing operation (no network round trip), so a
      // failure here is almost always a credential/config problem, not Kakao-style transient
      // unavailability - but API-08 only defines one error code for this path, unlike
      // API-42/43's 4xx/5xx split. Log only the exception class, never its message (S3
      // exception messages can embed request details).
      log.error("Profile image presign failed: exception={}", e.getClass().getSimpleName());
      throw new ProjectException(UploadErrorCode.ISSUANCE_UNAVAILABLE);
    }

    uploads.save(ProfileImageUpload.issue(userId, objectKey, contentType, fileSize, expiresAt));

    return new IssueUploadUrlResponse(
        uploadUrl,
        objectKey,
        "PUT",
        (int) UPLOAD_URL_TTL.toSeconds(),
        Map.of("Content-Type", contentType),
        MAX_FILE_SIZE);
  }

  // Called from UserService.updateProfile (API-06) when profileImageKey is present. Only
  // verifies ownership and the uploaded object itself; the caller still owns writing the key
  // onto the User entity and resolving its URL, since that's part of the same profile-update
  // transaction as nickname/ageRange.
  @Transactional
  public void link(Long userId, String objectKey) {
    ProfileImageUpload upload =
        uploads
            .findByUserIdAndObjectKeyAndStatus(userId, objectKey, ProfileImageUploadStatus.ISSUED)
            .orElseThrow(() -> new ProjectException(UploadErrorCode.NOT_OWNED));

    var head =
        storage
            .headObject(objectKey)
            .orElseThrow(() -> new ProjectException(UploadErrorCode.OBJECT_MISMATCH));
    if (head.contentLength() > MAX_FILE_SIZE || !upload.getContentType().equals(head.contentType()))
      throw new ProjectException(UploadErrorCode.OBJECT_MISMATCH);
    if (!matchesMagicBytes(upload.getContentType(), storage.leadingBytes(objectKey, 12)))
      throw new ProjectException(UploadErrorCode.OBJECT_MISMATCH);

    upload.markLinked();
  }

  @Transactional
  public ResetProfileImageResponse reset(Long userId) {
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    user.resetProfileImage();
    // updatedAt is DB-generated (@UpdateTimestamp(source = SourceType.DB)); flush now so the
    // UPDATE runs and Hibernate reads the fresh value back before the response is built.
    users.flush();
    return new ResetProfileImageResponse(user.getUserId(), null, user.getUpdatedAt());
  }

  // The declared Content-Type on the original PUT request can't be trusted on its own, so
  // check the stored object's actual leading bytes against each format's magic signature.
  private boolean matchesMagicBytes(String contentType, byte[] bytes) {
    return switch (contentType) {
      case "image/jpeg" ->
          bytes.length >= 3
              && (bytes[0] & 0xFF) == 0xFF
              && (bytes[1] & 0xFF) == 0xD8
              && (bytes[2] & 0xFF) == 0xFF;
      case "image/png" ->
          bytes.length >= 8
              && (bytes[0] & 0xFF) == 0x89
              && bytes[1] == 'P'
              && bytes[2] == 'N'
              && bytes[3] == 'G'
              && bytes[4] == 0x0D
              && bytes[5] == 0x0A
              && bytes[6] == 0x1A
              && bytes[7] == 0x0A;
      case "image/webp" ->
          bytes.length >= 12
              && bytes[0] == 'R'
              && bytes[1] == 'I'
              && bytes[2] == 'F'
              && bytes[3] == 'F'
              && bytes[8] == 'W'
              && bytes[9] == 'E'
              && bytes[10] == 'B'
              && bytes[11] == 'P';
      default -> false;
    };
  }
}
