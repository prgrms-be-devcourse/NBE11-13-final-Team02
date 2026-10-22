package kr.mujuthaek.api.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류 응답 형식 통일: Problem Details(RFC 9457) + 우리 오류 코드(code). */
@RestControllerAdvice
class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(CustomException.class)
  ProblemDetail handle(CustomException e) {
    return problem(e.errorCode().status(), e.errorCode(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handle(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse(ErrorCode.INVALID_REQUEST.message());
    return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, detail);
  }

  /** 읽을 수 없는 요청 본문(깨진 JSON), 주소·쿼리 값의 타입이 맞지 않음 → 400 (서버 오류가 아니에요). */
  @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
  ProblemDetail handleUnreadable(Exception e) {
    return problem(
        HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.message());
  }

  /**
   * 나머지. 스프링 MVC의 표준 요청 오류(ErrorResponse: 없는 주소 404, 지원하지 않는 메서드 405, 지원하지 않는 형식 415, 필수 값 없음 400
   * …)는 원래 상태 코드를 그대로 써요. 405의 Allow 같은 헤더도 함께 보내요. 그 밖의 예외만 500으로 기록해요.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handle(Exception e) {
    if (e instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
      HttpStatusCode status = error.getStatusCode();
      ErrorCode code =
          switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> ErrorCode.INVALID_REQUEST;
          };
      return ResponseEntity.status(status)
          .headers(error.getHeaders())
          .body(problem(status, code, code.message()));
    }
    log.error("처리하지 못한 오류", e);
    return ResponseEntity.internalServerError()
        .body(
            problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.message()));
  }

  private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code.name());
    return problem;
  }
}
