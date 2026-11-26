package com.hobbytree.hobbytree.domain.coach.controller;

import com.hobbytree.hobbytree.domain.coach.code.CoachSuccessCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachTranscriptionResponse;
import com.hobbytree.hobbytree.domain.coach.service.CoachTranscriptionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coach")
public class CoachTranscriptionController {
  private final CoachTranscriptionService service;

  @Operation(
      summary = "음성 질문을 텍스트로 변환",
      description = "60초·7MiB 이하 음성 file 한 개. 결과를 확인한 뒤 별도로 채팅을 전송합니다. 자동 재시도하지 않습니다.")
  @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<CoachTranscriptionResponse>> transcribe(
      @AuthenticationPrincipal Long userId,
      @RequestPart("file") MultipartFile file,
      org.springframework.web.multipart.MultipartHttpServletRequest request) {
    if (request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum() != 1)
      throw new com.hobbytree.hobbytree.global.exception.ProjectException(
          com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode.COMMON_BAD_REQUEST);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(CoachSuccessCode.TRANSCRIBED, service.transcribe(userId, file)));
  }
}
