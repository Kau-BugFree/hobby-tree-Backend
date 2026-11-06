package com.hobbytree.hobbytree.domain.hobby.controller;

import com.hobbytree.hobbytree.domain.hobby.code.HobbySuccessCode;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyListResponse;
import com.hobbytree.hobbytree.domain.hobby.service.HobbyService;
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
@RequestMapping("/api/v1/hobbies")
public class HobbyController {
  private final HobbyService service;

  @GetMapping
  public ResponseEntity<ApiResponse<HobbyListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer categoryId,
      @RequestParam(required = false) Integer maxDurationMinutes,
      @RequestParam(required = false) Integer maxCost,
      @RequestParam(required = false) String indoorOutdoor,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                HobbySuccessCode.LIST,
                service.list(
                    userId,
                    keyword,
                    categoryId,
                    maxDurationMinutes,
                    maxCost,
                    indoorOutdoor,
                    sort,
                    cursor,
                    size)));
  }
}
