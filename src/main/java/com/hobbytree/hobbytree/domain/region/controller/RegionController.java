package com.hobbytree.hobbytree.domain.region.controller;

import com.hobbytree.hobbytree.domain.region.code.RegionSuccessCode;
import com.hobbytree.hobbytree.domain.region.dto.RegionListResponse;
import com.hobbytree.hobbytree.domain.region.service.RegionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/regions")
public class RegionController {
  private final RegionService service;

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<RegionListResponse>> search(
      @AuthenticationPrincipal Long userId,
      @RequestParam String keyword,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) Long parentRegionId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                RegionSuccessCode.SEARCH,
                service.search(userId, keyword, level, parentRegionId, cursor, size)));
  }
}
