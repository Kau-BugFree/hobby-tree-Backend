package com.hobbytree.hobbytree.domain.user.controller;

import com.hobbytree.hobbytree.domain.activity.code.SavedActivitySuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.SavedActivityListResponse;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityService;
import com.hobbytree.hobbytree.domain.experience.code.ReservationSuccessCode;
import com.hobbytree.hobbytree.domain.experience.dto.MyReservationListResponse;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceReservationService;
import com.hobbytree.hobbytree.domain.notification.code.NotificationSuccessCode;
import com.hobbytree.hobbytree.domain.preference.code.PreferenceSuccessCode;
import com.hobbytree.hobbytree.domain.preference.dto.UpdatePreferencesRequest;
import com.hobbytree.hobbytree.domain.preference.dto.UpdatePreferencesResponse;
import com.hobbytree.hobbytree.domain.user.code.UserSuccessCode;
import com.hobbytree.hobbytree.domain.user.dto.MyProfileResponse;
import com.hobbytree.hobbytree.domain.user.dto.UpdateNotificationSettingsRequest;
import com.hobbytree.hobbytree.domain.user.dto.UpdateNotificationSettingsResponse;
import com.hobbytree.hobbytree.domain.user.dto.UpdateProfileRequest;
import com.hobbytree.hobbytree.domain.user.dto.UpdateProfileResponse;
import com.hobbytree.hobbytree.domain.user.dto.WithdrawResponse;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
  private final UserService service;
  private final ExperienceReservationService experienceReservations;
  private final SavedActivityService savedActivities;

  @GetMapping("/me/saved-activities")
  public ResponseEntity<ApiResponse<SavedActivityListResponse>> listMySavedActivities(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) Long hobbyId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                SavedActivitySuccessCode.LIST,
                savedActivities.listMine(userId, hobbyId, cursor, size)));
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(UserSuccessCode.PROFILE_VIEW, service.getMyProfile(userId)));
  }

  @PatchMapping("/me")
  public ResponseEntity<ApiResponse<UpdateProfileResponse>> updateProfile(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody UpdateProfileRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                UserSuccessCode.PROFILE_UPDATE, service.updateProfile(userId, request)));
  }

  @DeleteMapping("/me")
  public ResponseEntity<ApiResponse<WithdrawResponse>> withdraw(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(UserSuccessCode.WITHDRAW, service.withdraw(userId)));
  }

  @PatchMapping("/me/notification-settings")
  public ResponseEntity<ApiResponse<UpdateNotificationSettingsResponse>> updateNotificationSettings(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody UpdateNotificationSettingsRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                NotificationSuccessCode.SETTINGS_UPDATE,
                service.updateNotificationSettings(userId, request)));
  }

  @PutMapping("/me/preferences")
  public ResponseEntity<ApiResponse<UpdatePreferencesResponse>> updatePreferences(
      @AuthenticationPrincipal Long userId, @RequestBody UpdatePreferencesRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                PreferenceSuccessCode.SAVED, service.updatePreferences(userId, request)));
  }

  @GetMapping("/me/preferences")
  public ResponseEntity<ApiResponse<UpdatePreferencesResponse>> getPreferences(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(PreferenceSuccessCode.VIEW, service.getPreferences(userId)));
  }

  @GetMapping("/me/experience-reservations")
  public ResponseEntity<ApiResponse<MyReservationListResponse>> listMyExperienceReservations(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ReservationSuccessCode.LIST,
                experienceReservations.listMine(userId, status, cursor, size)));
  }
}
