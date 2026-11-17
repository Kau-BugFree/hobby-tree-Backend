package com.hobbytree.hobbytree.domain.auth.security;

import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthResponseWriter {
  private final ObjectMapper mapper;

  public AuthResponseWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void write(
      HttpServletResponse response, BaseErrorCode code, Object result, Long retryAfter)
      throws IOException {
    response.setStatus(code.getStatus().value());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    if (retryAfter != null) response.setHeader("Retry-After", retryAfter.toString());
    mapper.writeValue(response.getOutputStream(), ApiResponse.onFailure(code, result));
  }
}
