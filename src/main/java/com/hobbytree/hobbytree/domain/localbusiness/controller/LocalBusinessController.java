package com.hobbytree.hobbytree.domain.localbusiness.controller;

import com.hobbytree.hobbytree.domain.localbusiness.code.LocalBusinessSuccessCode;
import com.hobbytree.hobbytree.domain.localbusiness.dto.LocalBusinessListResponse;
import com.hobbytree.hobbytree.domain.localbusiness.dto.SelectLocalBusinessRequest;
import com.hobbytree.hobbytree.domain.localbusiness.dto.SelectLocalBusinessResponse;
import com.hobbytree.hobbytree.domain.localbusiness.service.LocalBusinessSearchService;
import com.hobbytree.hobbytree.domain.localbusiness.service.LocalBusinessSelectionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Issue #191: not part of API.md's 49-API spec. Location-based, Naver-backed business search
// with a bridge into the existing experience/reservation system - see the class Javadocs on
// LocalBusinessSearchService/LocalBusinessSelectionService.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/local-businesses")
public class LocalBusinessController {
  private final LocalBusinessSearchService search;
  private final LocalBusinessSelectionService selection;

  @GetMapping
  public ResponseEntity<ApiResponse<LocalBusinessListResponse>> search(
      @AuthenticationPrincipal Long userId,
      @RequestParam String region,
      @RequestParam String hobby) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                LocalBusinessSuccessCode.SEARCH, search.search(userId, region, hobby)));
  }

  @PostMapping("/select")
  public ResponseEntity<ApiResponse<SelectLocalBusinessResponse>> select(
      @Valid @RequestBody SelectLocalBusinessRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(LocalBusinessSuccessCode.SELECT, selection.select(request)));
  }
}
