package kr.mujuthaek.api.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류 응답 형식 통일: Problem Details(RFC 9457) + 우리 오류 코드(code). */
@RestControllerAdvice
class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(CustomException.class)
  ProblemDetail handle(CustomException e) {
    return problem(e.errorCode(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handle(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse(ErrorCode.INVALID_REQUEST.message());
    return problem(ErrorCode.INVALID_REQUEST, detail);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handle(Exception e) {
    log.error("처리하지 못한 오류", e);
    return problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
  }

  private static ProblemDetail problem(ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setProperty("code", code.name());
    return problem;
  }
}
