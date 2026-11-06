package com.hobbytree.hobbytree.domain.region.dto;

import java.util.List;

public record RegionListResponse(
    List<RegionItemResponse> items, String nextCursor, boolean hasNext) {}
