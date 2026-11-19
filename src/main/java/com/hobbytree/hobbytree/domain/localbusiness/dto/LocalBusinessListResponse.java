package com.hobbytree.hobbytree.domain.localbusiness.dto;

import java.util.List;

public record LocalBusinessListResponse(
    String region, String hobby, List<LocalBusinessResponse> items) {}
