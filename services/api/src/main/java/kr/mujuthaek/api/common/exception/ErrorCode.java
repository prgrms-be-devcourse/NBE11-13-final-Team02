package kr.mujuthaek.api.common.exception;

import org.springframework.http.HttpStatus;

/** 우리 오류 코드. 모듈마다 필요한 코드를 여기에 추가해요 (접두어로 모듈 구분). */
public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않아요"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없어요"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 났어요"),

  // notice
  NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공고를 찾을 수 없어요"),
  ;

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus status() {
    return status;
  }

  public String message() {
    return message;
  }
}
