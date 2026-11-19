package com.hobbytree.hobbytree.domain.activity.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityCompletionReceiptRepository {
  private final JdbcTemplate jdbc;

  public Optional<Receipt> find(long recordId) {
    return jdbc
        .query(
            "SELECT request_payload::text,response_payload::text FROM activity_completion_receipts WHERE record_id=? AND schema_version=1",
            (row, i) -> new Receipt(row.getString(1), row.getString(2)),
            recordId)
        .stream()
        .findFirst();
  }

  public void save(long recordId, String request, String response) {
    jdbc.update(
        "INSERT INTO activity_completion_receipts(record_id,request_payload,response_payload) VALUES(?,?::jsonb,?::jsonb)",
        recordId,
        request,
        response);
  }

  public record Receipt(String request, String response) {}
}
