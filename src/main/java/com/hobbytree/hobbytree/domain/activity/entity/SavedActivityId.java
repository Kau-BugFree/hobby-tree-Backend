package com.hobbytree.hobbytree.domain.activity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SavedActivityId implements Serializable {
  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "activity_id", nullable = false)
  private Long activityId;
}
