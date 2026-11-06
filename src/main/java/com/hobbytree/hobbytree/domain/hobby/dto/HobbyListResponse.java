package com.hobbytree.hobbytree.domain.hobby.dto;

import java.util.List;

public record HobbyListResponse(
    List<HobbyItemResponse> items, String nextCursor, boolean hasNext) {}
