package kr.mujuthaek.api.user.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.mujuthaek.api.common.exception.ErrorCode;

/** 보안 필터에서 나는 401·403도 GlobalExceptionHandler와 같은 Problem Details 모양으로 보내요. */
final class ProblemResponses {

  private ProblemResponses() {}

  static void write(HttpServletResponse response, ErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            """
            {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(
                    code.status().getReasonPhrase(),
                    code.status().value(),
                    code.message(),
                    code.name()));
  }
}
