package com.hobbytree.hobbytree.domain.sponsoredcontent.entity;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "activity_sponsored_contents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivitySponsoredContent {

  @EmbeddedId private ActivitySponsoredContentId id;

  @MapsId("activityId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "activity_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Activity activity;

  @MapsId("sponsoredContentId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sponsored_content_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private SponsoredContent sponsoredContent;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;
}
