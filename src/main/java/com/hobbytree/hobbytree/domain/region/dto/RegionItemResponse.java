package com.hobbytree.hobbytree.domain.region.dto;

public record RegionItemResponse(
    Long regionId, String name, String fullName, String level, Long parentRegionId) {}
