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
import com.hobbytree.hobbytree.global.ratelimit.RateLimiter;
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
  public String resolveUrl(String objectKey) {
    if (objectKey == null) return null;
    try {
      return storage.presignDownload(objectKey, Duration.ofMinutes(15));
    } catch (SdkException e) {
      log.warn("Profile image download presign failed: exception={}", e.getClass().getSimpleName());
      throw new ProjectException(UploadErrorCode.ISSUANCE_UNAVAILABLE);
    }
  }

  private static final long MAX_FILE_SIZE = 5_242_880L;
  private static final int MAX_FILE_NAME_LENGTH = 100;
  private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
  private static final String RATE_BUCKET = "profile-image-upload-url";
  private static final int RATE_LIMIT = 10;
  private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");
  private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final UserRepository users;
  private final ProfileImageUploadRepository uploads;
  private final ProfileImageStorageGateway storage;
  private final Clock clock;
  private final RateLimiter rateLimiter;

  @Transactional
  public IssueUploadUrlResponse issueUploadUrl(Long userId, IssueUploadUrlRequest request) {
    rateLimiter.require(RATE_BUCKET, userId, RATE_LIMIT, RATE_WINDOW);
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

  // Called from UserService.updateProfile (API-06) when profileImageKey is present. Split into
  // three steps (issue #274) so the S3 round trips below never run while UserService holds the
  // user row locked in its own transaction - the same "prepare / external call / finish" shape
  // #185/#269 used for Redis, except here the external call's result (finalKey) must still be
  // verified before the caller's own commit, so a middle, non-transactional step carries it.

  public record LinkPreparation(String objectKey, String contentType) {}

  // Read-only ownership/status check before spending an S3 copy. Joins whichever transaction
  // the caller (UserService's own short "prepare" step) already has open.
  public LinkPreparation prepareLink(Long userId, String objectKey) {
    ProfileImageUpload upload =
        uploads
            .findByUserIdAndObjectKeyAndStatus(userId, objectKey, ProfileImageUploadStatus.ISSUED)
            .orElseThrow(() -> new ProjectException(UploadErrorCode.NOT_OWNED));
    return new LinkPreparation(objectKey, upload.getContentType());
  }

  // No DB access, so this must never run inside a transaction - it's the whole point of the
  // split. Verifies the uploaded object itself, then copies it to a key the client never had a
  // presigned PUT URL for - the original presigned URL stays valid for the rest of its TTL, so
  // without this copy a client could re-PUT unverified content to the same key after this
  // already approved it, and have that content served under the linked profileImageUrl.
  public String copyToFinalKey(Long userId, String objectKey, String contentType) {
    String finalKey = finalKey(userId, contentType);
    try {
      var head =
          storage
              .headObject(objectKey)
              .orElseThrow(() -> new ProjectException(UploadErrorCode.OBJECT_MISMATCH));
      // A presigned PUT URL doesn't enforce a minimum body length, so a 0-byte upload would
      // otherwise pass this size check and then make leadingBytes request an empty object's
      // first 12 bytes, which S3 rejects as an invalid range.
      if (head.contentLength() < 1
          || head.contentLength() > MAX_FILE_SIZE
          || !contentType.equals(head.contentType()))
        throw new ProjectException(UploadErrorCode.OBJECT_MISMATCH);
      // headObject, leadingBytes and copyObject aren't atomic, and the source key's presigned
      // PUT URL stays valid for the rest of its TTL - pinning every later read/copy to the
      // eTag headObject just saw means a re-PUT in any of these gaps makes S3 reject the next
      // call with 412 instead of silently validating or copying replaced content.
      if (!matchesMagicBytes(contentType, storage.leadingBytes(objectKey, 12, head.eTag())))
        throw new ProjectException(UploadErrorCode.OBJECT_MISMATCH);

      storage.copyObject(objectKey, finalKey, head.eTag());
      return finalKey;
    } catch (SdkException e) {
      // Any S3 failure while reading/copying the object back (including a 412 from the eTag
      // pin above, the invalid-range case if contentLength somehow still slipped through, or
      // a transient S3 error) means we can't confirm the final key holds what was validated -
      // from this endpoint's contract, that's exactly what UPLOAD400_1 means, not a generic
      // 500.
      log.warn("Profile image verification failed: exception={}", e.getClass().getSimpleName());
      throw new ProjectException(UploadErrorCode.OBJECT_MISMATCH);
    }
  }

  // Re-checks ownership/status right before marking linked, guarding the gap since
  // prepareLink (e.g. a concurrent duplicate request already linked the same upload while this
  // one was copying the S3 object). Joins the caller's own "finish" transaction - the same one
  // that writes user.linkProfileImage(finalKey) - so both commit or roll back together.
  // Records finalKey (issue #277) so ProfileImageCleanupService can later tell whether this
  // copy is still the user's current image or was superseded by a later upload.
  public void finishLink(Long userId, String objectKey, String finalKey) {
    ProfileImageUpload upload =
        uploads
            .findByUserIdAndObjectKeyAndStatus(userId, objectKey, ProfileImageUploadStatus.ISSUED)
            .orElseThrow(() -> new ProjectException(UploadErrorCode.NOT_OWNED));
    upload.markLinked(finalKey);
  }

  private String finalKey(Long userId, String contentType) {
    return "profiles/%d/final/%s.%s"
        .formatted(userId, UUID.randomUUID(), EXTENSION_BY_CONTENT_TYPE.get(contentType));
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
