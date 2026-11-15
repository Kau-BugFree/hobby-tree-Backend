package com.hobbytree.hobbytree.domain.experience.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// API.md's "experience_providers (신규 제안)": an admin-curated name lookup, not a real
// provider account/ownership system (hobbytree-team-decisions-2026-11-04 - no real
// partner/provider integration planned for MVP).
@Entity
@Table(name = "experience_providers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperienceProvider {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "provider_id", nullable = false)
  private Long providerId;

  @Column(name = "name", nullable = false, length = 160)
  private String name;
}
