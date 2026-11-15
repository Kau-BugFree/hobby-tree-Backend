package com.hobbytree.hobbytree.domain.experience.dto;

import java.util.List;

public record MyReservationListResponse(
    List<MyReservationItemResponse> items, String nextCursor, boolean hasNext) {}
