package com.hobbytree.hobbytree.domain.experience.entity;

public enum ReservationStatus {
  // Preserved legacy state; API-37 creates REDIRECTED/PENDING_VERIFICATION only.
  REQUESTED,
  // API-37: no externalReservationId submitted - client is redirected to the provider's own
  // booking flow with nothing yet to verify.
  REDIRECTED,
  // API-37: an externalReservationId was submitted but not yet provider-confirmed. No
  // provider callback exists in the current API set, so nothing in this codebase promotes a
  // row out of this status today - see ExperienceReservationService's class comment.
  PENDING_VERIFICATION,
  CONFIRMED,
  CANCELLED,
  ATTENDED
}
