package com.hobbytree.hobbytree.domain.hobby.entity;

import com.hobbytree.hobbytree.domain.hobby.dto.HobbyCategoryResponse;
import java.util.List;
import java.util.Map;

// Single source of truth for the 3 fixed MVP categories (hobbytree-team-decisions-2026-11-04)
// and their stable categoryId - shared by HobbyService (API-16/17's filter/response) and
// OnboardingService (API-10's categories option list), instead of each keeping its own copy.
public final class HobbyCategoryCatalog {
  public static final Map<Integer, HobbyCategory> BY_ID =
      Map.of(1, HobbyCategory.COOKING, 2, HobbyCategory.INSTRUMENT, 3, HobbyCategory.READING);
  public static final Map<HobbyCategory, Integer> ID_BY_CATEGORY =
      Map.of(HobbyCategory.COOKING, 1, HobbyCategory.INSTRUMENT, 2, HobbyCategory.READING, 3);
  public static final Map<HobbyCategory, String> NAME =
      Map.of(
          HobbyCategory.COOKING, "요리", HobbyCategory.INSTRUMENT, "악기", HobbyCategory.READING, "독서");

  // Declared order matters here (1, 2, 3), unlike the lookup maps above - this is API-10's
  // categories list as returned to the client.
  public static final List<HobbyCategoryResponse> ALL =
      List.of(
          new HobbyCategoryResponse(1, "요리"),
          new HobbyCategoryResponse(2, "악기"),
          new HobbyCategoryResponse(3, "독서"));

  private HobbyCategoryCatalog() {}
}
