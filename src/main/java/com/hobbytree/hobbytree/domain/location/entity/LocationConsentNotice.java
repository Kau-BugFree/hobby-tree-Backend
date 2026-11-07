package com.hobbytree.hobbytree.domain.location.entity;

// API-10 (온보딩 선택지 조회, not yet implemented) will own the real
// locationConsentNotice.version; until then this is the one version API-05's read-side
// placeholder and API-43's write-side version check agree on. Update both call sites together
// when API-10 ships.
public final class LocationConsentNotice {
  public static final String CURRENT_VERSION = "1.0";

  private LocationConsentNotice() {}
}
