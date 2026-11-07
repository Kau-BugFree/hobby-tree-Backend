package com.hobbytree.hobbytree.domain.location.controller;

import com.hobbytree.hobbytree.domain.location.code.LocationSuccessCode;
import com.hobbytree.hobbytree.domain.location.dto.RecordLocationConsentRequest;
import com.hobbytree.hobbytree.domain.location.dto.RecordLocationConsentResponse;
import com.hobbytree.hobbytree.domain.location.service.LocationConsentService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
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
@RequestMapping("/api/v1/users/me/location-consents")
public class LocationConsentController {
  private final LocationConsentService service;

  @PostMapping
  public ResponseEntity<ApiResponse<RecordLocationConsentResponse>> record(
      @AuthenticationPrincipal Long userId, @RequestBody RecordLocationConsentRequest request) {
    var code = LocationSuccessCode.CONSENT_RECORDED;
    return ResponseEntity.status(code.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(code, service.record(userId, request)));
  }
}
