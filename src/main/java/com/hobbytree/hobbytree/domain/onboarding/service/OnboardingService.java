package com.hobbytree.hobbytree.domain.onboarding.service;

import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategoryCatalog;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentNotice;
import com.hobbytree.hobbytree.domain.location.entity.LocationConsentType;
import com.hobbytree.hobbytree.domain.onboarding.dto.AvailableTimeOptionResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.BudgetOptionResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.DifficultyOptionResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.IndoorOutdoorOptionResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.LocationConsentNoticeResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.OnboardingOptionsResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.TermsResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

// Static options and a versioned consent catalog. Policy drafts explicitly mark unresolved
// operational details as 미정; consent history is persisted separately by API-13.
@Service
public class OnboardingService {
  // Bump whenever the static option content below changes. Public: API-11 compares a
  // submitted optionsVersion against this exact constant (PREFERENCE409_1 on mismatch).
  public static final String OPTIONS_VERSION = "2026-11-07";

  // IDs remain stable. Versions distinguish these drafts from the previous placeholder text.
  // API-13 validates required declarations and persists optional consent separately.
  public static final List<TermsResponse> TERMS =
      List.of(
          new TermsResponse(1, "서비스 이용약관", "1.1", true, OnboardingTermsContent.SERVICE),
          new TermsResponse(2, "개인정보 수집·이용 동의", "1.1", true, OnboardingTermsContent.PRIVACY),
          new TermsResponse(3, "만 19세 이상 확인", "1.0", true, OnboardingTermsContent.AGE),
          new TermsResponse(4, "학습용 데이터셋 활용 동의", "1.0", false, OnboardingTermsContent.TRAINING));
  public static final Map<Integer, TermsResponse> TERMS_BY_ID =
      TERMS.stream().collect(Collectors.toMap(TermsResponse::termsId, Function.identity()));

  private static final LocationConsentNoticeResponse LOCATION_CONSENT_NOTICE =
      new LocationConsentNoticeResponse(
          LocationConsentType.NEARBY_EXPERIENCES.name(),
          LocationConsentNotice.CURRENT_VERSION,
          OnboardingTermsContent.LOCATION);

  // Public: API-14 uses monthlyMax per budgetLevel as a per-activity-cost-ceiling heuristic
  // when scoring candidates (a rough proxy, not an exact budget translation).
  public static final List<BudgetOptionResponse> BUDGET_OPTIONS =
      List.of(
          new BudgetOptionResponse("LOW", "월 3만 원 이하", 0, 30000),
          new BudgetOptionResponse("MEDIUM", "월 3만~10만 원", 30001, 100000),
          new BudgetOptionResponse("HIGH", "월 10만 원 초과", 100001, null));
  public static final Map<String, BudgetOptionResponse> BUDGET_OPTIONS_BY_VALUE =
      BUDGET_OPTIONS.stream()
          .collect(Collectors.toMap(BudgetOptionResponse::value, Function.identity()));

  private static final List<AvailableTimeOptionResponse> AVAILABLE_TIME_OPTIONS =
      List.of(
          new AvailableTimeOptionResponse(15, "15분"),
          new AvailableTimeOptionResponse(30, "30분"),
          new AvailableTimeOptionResponse(60, "1시간"),
          new AvailableTimeOptionResponse(120, "2시간"));

  private static final List<IndoorOutdoorOptionResponse> INDOOR_OUTDOOR_OPTIONS =
      List.of(
          new IndoorOutdoorOptionResponse("INDOOR", "실내"),
          new IndoorOutdoorOptionResponse("OUTDOOR", "실외"),
          new IndoorOutdoorOptionResponse("ANY", "상관없음"));

  private static final List<DifficultyOptionResponse> DIFFICULTY_OPTIONS =
      List.of(
          new DifficultyOptionResponse("BEGINNER", "입문"),
          new DifficultyOptionResponse("INTERMEDIATE", "경험 있음"),
          new DifficultyOptionResponse("ANY", "상관없음"));

  private static final OnboardingOptionsResponse OPTIONS =
      new OnboardingOptionsResponse(
          OPTIONS_VERSION,
          BUDGET_OPTIONS,
          AVAILABLE_TIME_OPTIONS,
          INDOOR_OUTDOOR_OPTIONS,
          DIFFICULTY_OPTIONS,
          HobbyCategoryCatalog.ALL,
          TERMS,
          LOCATION_CONSENT_NOTICE);

  public OnboardingOptionsResponse options() {
    return OPTIONS;
  }
}
