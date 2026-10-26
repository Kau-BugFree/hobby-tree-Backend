package com.hobbytree.hobbytree.domain.activity.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "activity_materials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityMaterial {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "activity_material_id", nullable = false)
  private Long activityMaterialId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "activity_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Activity activity;

  @Column(name = "material_name", nullable = false, length = 160)
  private String materialName;

  @Column(name = "quantity_description", nullable = true, length = 160)
  private String quantityDescription;

  @ColumnDefault("TRUE")
  @Column(name = "is_required", nullable = false)
  private Boolean isRequired = true;

  @Column(name = "acquisition_type", nullable = true, length = 30)
  private String acquisitionType;
}
