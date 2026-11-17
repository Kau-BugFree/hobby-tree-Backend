package com.hobbytree.hobbytree.domain.localbusiness.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The frontend resends the chosen search result's own fields (Naver gives no stable business
// id), plus the hobby keyword the search was made with.
public record SelectLocalBusinessRequest(
    @NotBlank @Size(max = 20) String hobby,
    @NotBlank @Size(max = 160) String name,
    @Size(max = 100) String category,
    @Size(max = 300) String address,
    @NotBlank @Size(max = 50) String mapX,
    @NotBlank @Size(max = 50) String mapY) {}
