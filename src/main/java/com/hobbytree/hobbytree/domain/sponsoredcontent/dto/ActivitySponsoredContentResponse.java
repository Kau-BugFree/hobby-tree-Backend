package com.hobbytree.hobbytree.domain.sponsoredcontent.dto;

import java.util.List;

public record ActivitySponsoredContentResponse(
    Long activityId, List<SponsoredContentItemResponse> items) {}
