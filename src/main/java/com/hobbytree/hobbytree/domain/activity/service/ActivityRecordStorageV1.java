package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.dto.ActivityDetailResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Version 1 snapshots and immutable receipts encode growth stages as 1..6. */
final class ActivityRecordStorageV1 {
  private ActivityRecordStorageV1() {}

  static ObjectNode snapshot(ObjectMapper mapper, ActivityDetailResponse detail) {
    ObjectNode snapshot = (ObjectNode) mapper.valueToTree(detail);
    snapshot.put("stageNumber", detail.stageNumber() + 1);
    snapshot.remove("isSaved");
    snapshot.remove("steps");
    return snapshot;
  }

  static String writeReceipt(ObjectMapper mapper, Object response) {
    ObjectNode payload = (ObjectNode) mapper.valueToTree(response);
    convertGrowth(payload, 0, 1);
    return mapper.writeValueAsString(payload);
  }

  static <T> T readReceipt(ObjectMapper mapper, String json, Class<T> type) {
    ObjectNode payload = mapper.readValue(json, ObjectNode.class);
    convertGrowth(payload, 1, -1);
    return mapper.treeToValue(payload, type);
  }

  private static void convertGrowth(ObjectNode payload, int firstStage, int offset) {
    ObjectNode growth = (ObjectNode) payload.required("growth");
    for (String key : new String[] {"previousStageNumber", "currentStageNumber"}) {
      var number = growth.required(key);
      if (!number.isIntegralNumber()
          || !number.canConvertToInt()
          || number.intValue() < firstStage
          || number.intValue() > firstStage + 5) {
        throw new IllegalArgumentException("Invalid growth stage in activity receipt");
      }
      growth.put(key, number.intValue() + offset);
    }
  }
}
