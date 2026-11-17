package com.hobbytree.hobbytree.domain.localbusiness.dto;

public record LocalBusinessResponse(
    String name,
    String category,
    String address,
    String roadAddress,
    String telephone,
    String mapX,
    String mapY) {}
