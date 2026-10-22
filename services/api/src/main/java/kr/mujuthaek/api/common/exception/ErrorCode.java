package kr.mujuthaek.api.common.exception;

import org.springframework.http.HttpStatus;

/** 우리 오류 코드. 모듈마다 필요한 코드를 여기에 추가해요 (접두어로 모듈 구분). */
public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않아요"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없어요"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 났어요"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요"),

  // notice
  NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공고를 찾을 수 없어요"),

  // user
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없어요"),
  UNSUPPORTED_SOCIAL_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인이에요"),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요"),
  WITHDRAWAL_PROVIDER_NOT_LINKED(HttpStatus.BAD_REQUEST, "연결되지 않은 소셜 계정이에요"),
  WITHDRAWAL_TICKET_INVALID(HttpStatus.BAD_REQUEST, "탈퇴 요청이 만료됐어요. 다시 시도해 주세요"),
  WITHDRAWAL_ACCOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "탈퇴하려는 계정과 다시 로그인한 계정이 달라요"),
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
