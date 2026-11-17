package com.hobbytree.hobbytree.domain.hobby.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
    name = "hobby_stages",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"hobby_id", "stage_number"})})
@Check(constraints = "(stage_number BETWEEN 0 AND 5)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HobbyStage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "hobby_stage_id", nullable = false)
  private Long hobbyStageId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hobby_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Hobby hobby;

  @Column(name = "stage_number", nullable = false)
  private Short stageNumber;

  @Column(name = "stage_name", nullable = false, length = 100)
  private String stageName;

  @Column(name = "stage_description", nullable = true, columnDefinition = "text")
  private String stageDescription;

  @Column(name = "tree_image_key", nullable = true, columnDefinition = "text")
  private String treeImageKey;
}
