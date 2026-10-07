package com.hobbytree.hobbytree.global.exception;

import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GeneralExceptionAdvice {

  // JSON 파싱 실패, 필수 파라미터/헤더 누락, 파라미터 타입 오류
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    ServletRequestBindingException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
    BaseErrorCode code = GeneralErrorCode.COMMON_BAD_REQUEST;
    return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, null));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodValidation(
      HandlerMethodValidationException e) {
    // 반환값 검증 실패는 요청 오류가 아닌 서버 오류입니다.
    if (e.isForReturnValue()) {
      return handleException(e);
    }
    return handleBadRequest(e);
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
    BaseErrorCode code = GeneralErrorCode.COMMON_NOT_FOUND;
    return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, null));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    BaseErrorCode code = GeneralErrorCode.COMMON_METHOD_NOT_ALLOWED;
    return ResponseEntity.status(code.getStatus())
        .headers(e.getHeaders())
        .body(ApiResponse.onFailure(code, null));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException e) {
    BaseErrorCode code = GeneralErrorCode.COMMON_UNSUPPORTED_MEDIA_TYPE;
    return ResponseEntity.status(code.getStatus())
        .headers(e.getHeaders())
        .body(ApiResponse.onFailure(code, null));
  }

  // 직접 정의한 비즈니스 예외 처리
  @ExceptionHandler(ProjectException.class)
  public ResponseEntity<ApiResponse<Void>> handleProjectException(ProjectException e) {

    BaseErrorCode code = e.getErrorCode();

    return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, null));
  }

  // @Valid 검증 실패 처리
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
      MethodArgumentNotValidException e) {

    BaseErrorCode code = GeneralErrorCode.COMMON_BAD_REQUEST;

    Map<String, String> errors =
        e.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    error -> error.getDefaultMessage() == null ? "" : error.getDefaultMessage(),
                    (existing, replacement) -> existing));

    return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, errors));
  }

  // 예상하지 못한 서버 오류 처리
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {

    log.error("처리되지 않은 예외가 발생했습니다.", e);

    BaseErrorCode code = GeneralErrorCode.COMMON_INTERNAL_SERVER_ERROR;

    return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, null));
  }
}
