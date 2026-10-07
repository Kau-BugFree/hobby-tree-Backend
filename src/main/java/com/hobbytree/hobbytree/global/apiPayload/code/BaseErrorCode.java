package com.hobbytree.hobbytree.global.apiPayload.code;

import org.springframework.http.HttpStatus;

public interface BaseErrorCode {

  HttpStatus getStatus();

  String getCode();

  String getMessage();
}
