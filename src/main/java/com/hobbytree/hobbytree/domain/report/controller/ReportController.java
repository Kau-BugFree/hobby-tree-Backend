package com.hobbytree.hobbytree.domain.report.controller;

import com.hobbytree.hobbytree.domain.report.code.ReportSuccessCode;
import com.hobbytree.hobbytree.domain.report.dto.ReportRequest;
import com.hobbytree.hobbytree.domain.report.dto.ReportResponse;
import com.hobbytree.hobbytree.domain.report.service.ReportService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final ReportService service;

  @PostMapping
  public ResponseEntity<ApiResponse<ReportResponse>> report(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody ReportRequest request) {
    var code = ReportSuccessCode.RECEIVED;
    return ResponseEntity.status(code.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(code, service.report(userId, request)));
  }
}
