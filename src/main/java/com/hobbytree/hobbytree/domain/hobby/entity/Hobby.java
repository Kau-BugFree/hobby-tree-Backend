package com.hobbytree.hobbytree.domain.hobby.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "hobbies")
@Check(constraints = "(activities_per_stage > 0)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hobby {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "hobby_id", nullable = false)
  private Long hobbyId;

  @Column(name = "hobby_code", nullable = false, length = 50, unique = true)
  private String hobbyCode;

  @Column(name = "hobby_name", nullable = false, length = 100)
  private String hobbyName;

  @Column(name = "description", nullable = true, columnDefinition = "text")
  private String description;

  @ColumnDefault("3")
  @Column(name = "activities_per_stage", nullable = false)
  private Short activitiesPerStage = (short) 3;

  @ColumnDefault("TRUE")
  @Column(name = "is_supported", nullable = false)
  private Boolean isSupported = true;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
