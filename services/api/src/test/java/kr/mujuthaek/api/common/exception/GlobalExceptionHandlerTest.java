package kr.mujuthaek.api.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void wrongMethodIs405WithAllowHeader() {
    var response =
        handler.handle(new HttpRequestMethodNotSupportedException("GET", Set.of("POST")));

    assertThat(response.getStatusCode().value()).isEqualTo(405);
    assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
    assertThat(response.getBody().getProperties()).containsEntry("code", "METHOD_NOT_ALLOWED");
  }

  @Test
  void unsupportedMediaTypeKeepsItsStatus() {
    var response =
        handler.handle(
            new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

    assertThat(response.getStatusCode().value()).isEqualTo(415);
    assertThat(response.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");
  }

  @Test
  void brokenJsonBodyIs400() {
    var problem =
        handler.handleUnreadable(
            new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])));

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
  }

  @Test
  void unexpectedErrorIs500() {
    var response = handler.handle(new IllegalStateException("boom"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(response.getBody().getDetail()).doesNotContain("boom"); // 내부 메시지는 응답에 싣지 않아요
  }
}
