package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordListResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordListRepository;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCursor.Position;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.ActivityStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityRecordListService {
  private final UserRepository users;
  private final ActivityRecordListRepository records;
  private final ActivityRecordCursor cursors;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ActivityRecordListResponse list(
      Long userId, long treeId, String status, String cursor, String size) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
    int pageSize;
    try {
      if (status != null) ActivityStatus.valueOf(status);
      pageSize = size == null ? 20 : Integer.parseInt(size);
      if (pageSize < 1 || pageSize > 50) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
    if (!records.ownsTree(userId, treeId)) throw new ProjectException(TreeErrorCode.NOT_FOUND);
    var after = cursors.decode(cursor, userId, treeId, status);
    var rows = records.page(userId, treeId, status, after, pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    var items = rows.stream().limit(pageSize).toList();
    String nextCursor = null;
    if (hasNext) {
      var last = items.getLast();
      nextCursor =
          cursors.encode(userId, treeId, status, new Position(last.startedAt(), last.recordId()));
    }
    return new ActivityRecordListResponse(treeId, items, nextCursor, hasNext);
  }
}
