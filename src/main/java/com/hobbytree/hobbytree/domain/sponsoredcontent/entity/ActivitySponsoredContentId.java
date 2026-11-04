package com.hobbytree.hobbytree.domain.sponsoredcontent.entity;

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
public class ActivitySponsoredContentId implements Serializable {
  @Column(name = "activity_id", nullable = false)
  private Long activityId;

  @Column(name = "sponsored_content_id", nullable = false)
  private Long sponsoredContentId;
}
