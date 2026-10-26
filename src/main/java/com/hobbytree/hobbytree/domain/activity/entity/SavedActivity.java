package com.hobbytree.hobbytree.domain.activity.entity;

import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "saved_activities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedActivity {

  @EmbeddedId private SavedActivityId id;

  @MapsId("userId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @MapsId("activityId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "activity_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Activity activity;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "saved_at", nullable = false)
  private Instant savedAt;
}
