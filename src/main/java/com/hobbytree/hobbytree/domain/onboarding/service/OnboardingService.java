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

// Entirely static reference data for now (confirmed budget tiers and category list per
// hobbytree-team-decisions-2026-11-04; everything else - terms/locationConsentNotice content,
// optionsVersion - is a placeholder pending real values from PM). No repository, no DB table:
// a hobby_categories/onboarding_options/terms table would just be re-seeding placeholders that
// are expected to change wholesale once PM provides real terms, which is easier to do here in
// code than through a migration.
@Service
public class OnboardingService {
  // Bump whenever the static option content below changes. Public: API-11 compares a
  // submitted optionsVersion against this exact constant (PREFERENCE409_1 on mismatch).
  public static final String OPTIONS_VERSION = "2026-11-07";

  // PLACEHOLDER pending PM: real ToS/privacy-policy/location-consent URLs, versions and
  // required-consent flags are not yet provided (hobbytree-onboarding-options-pending).
  // Public: API-13 validates submitted agreements against this exact catalog (which terms
  // are active, which are required, and their current version).
  public static final List<TermsResponse> TERMS =
      List.of(
          new TermsResponse(1, "서비스 이용약관", "1.0", true, "https://example.com/terms/service"),
          new TermsResponse(
              2, "개인정보 처리 안내 및 필요한 동의", "1.0", true, "https://example.com/terms/privacy"));
  public static final Map<Integer, TermsResponse> TERMS_BY_ID =
      TERMS.stream().collect(Collectors.toMap(TermsResponse::termsId, Function.identity()));

  // PLACEHOLDER contentUrl pending PM; version is NOT a placeholder - it must stay equal to
  // LocationConsentNotice.CURRENT_VERSION, the same constant API-42/43 compare against.
  private static final LocationConsentNoticeResponse LOCATION_CONSENT_NOTICE =
      new LocationConsentNoticeResponse(
          LocationConsentType.NEARBY_EXPERIENCES.name(),
          LocationConsentNotice.CURRENT_VERSION,
          "https://example.com/terms/location");

  private static final List<BudgetOptionResponse> BUDGET_OPTIONS =
      List.of(
          new BudgetOptionResponse("LOW", "월 3만 원 이하", 0, 30000),
          new BudgetOptionResponse("MEDIUM", "월 3만~10만 원", 30001, 100000),
          new BudgetOptionResponse("HIGH", "월 10만 원 초과", 100001, null));

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
