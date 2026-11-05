package com.hobbytree.hobbytree.domain.sponsoredcontent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SponsoredContentItemResponse(
    Long sponsoredContentId,
    String title,
    String description,
    String sponsorName,
    String thumbnailUrl,
    String contentType,
    @JsonProperty("isSponsored") boolean isSponsored,
    String disclosureLabel,
    String externalUrl,
    Integer sortOrder) {}
