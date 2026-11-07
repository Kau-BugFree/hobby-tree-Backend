package com.hobbytree.hobbytree.domain.hobby.dto;

/** Converts stored growth stages only when mapping an HTTP response. */
public final class ApiStageNumber {
  private ApiStageNumber() {}

  /** The argument must be a stored 0-based stage, never an already mapped response value. */
  public static short fromStored(short storedStageNumber) {
    if (storedStageNumber < 0 || storedStageNumber > 5) {
      throw new IllegalArgumentException("Stored growth stage must be between 0 and 5");
    }
    return (short) (storedStageNumber + 1);
  }
}
