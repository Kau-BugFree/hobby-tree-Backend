package com.hobbytree.hobbytree.domain.profileimage.dto;

import java.util.Map;

public record IssueUploadUrlResponse(
    String uploadUrl,
    String objectKey,
    String method,
    int expiresIn,
    Map<String, String> requiredHeaders,
    long maxFileSize) {}
