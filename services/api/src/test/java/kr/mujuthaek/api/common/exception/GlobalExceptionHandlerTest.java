package kr.mujuthaek.api.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  record Applicant(@Min(0) Long personalIncomeAnnualWon, @NotNull String regionCode) {}

  record Evaluate(@Valid @NotNull Applicant applicant) {}

  @RestController
  static class SampleController {
    @PostMapping("/api/v1/notices/{id}/evaluate")
    String evaluate(@PathVariable String id, @Valid @RequestBody Evaluate body) {
      return "ok";
    }

    @GetMapping("/api/v1/notices/{id}")
    String notice(@PathVariable String id) {
      if (id.equals("boom")) {
        throw new IllegalStateException("DB 연결 끊김");
      }
      throw new CustomException(ErrorCode.NOTICE_NOT_FOUND);
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SampleController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void validationErrorsListFieldsAndRulesButNotValues() throws Exception {
    String body = "{\"applicant\":{\"personalIncomeAnnualWon\":-987654,\"regionCode\":null}}";
    var result =
        mvc.perform(
                post("/api/v1/notices/01J/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://mujuthaek.kr/problems/validation-failed"))
            .andExpect(jsonPath("$.title").value("입력값이 올바르지 않아요"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.instance").value("/api/v1/notices/01J/evaluate"))
            .andReturn();

    String json = result.getResponse().getContentAsString();
    assertThat(json)
        .contains(
            "{\"field\":\"applicant.personalIncomeAnnualWon\",\"reason\":\"MIN\"}",
            "{\"field\":\"applicant.regionCode\",\"reason\":\"NOT_NULL\"}")
        .doesNotContain("987654");
  }

  @Test
  void unreadableBodyIsAValidationFailure() throws Exception {
    mvc.perform(
            post("/api/v1/notices/01J/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicant\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void customErrorsUseTheirOwnCode() throws Exception {
    mvc.perform(get("/api/v1/notices/01J"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"))
        .andExpect(jsonPath("$.type").value("https://mujuthaek.kr/problems/notice-not-found"))
        .andExpect(jsonPath("$.title").value("공고를 찾을 수 없어요"));
  }

  @Test
  void serverErrorsShowOnlyTheCodeAndTime() throws Exception {
    var result =
        mvc.perform(get("/api/v1/notices/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("DB 연결");
  }

  @Test
  void constraintNamesBecomeUpperSnakeReasons() {
    assertThat(GlobalExceptionHandler.reason("Min")).isEqualTo("MIN");
    assertThat(GlobalExceptionHandler.reason("NotNull")).isEqualTo("NOT_NULL");
    assertThat(GlobalExceptionHandler.reason("DecimalMax")).isEqualTo("DECIMAL_MAX");
    assertThat(GlobalExceptionHandler.reason(null)).isEqualTo("INVALID");
  }

  @Test
  void everyCodeHasAProblemType() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.type()).startsWith("https://mujuthaek.kr/problems/").doesNotContain("_");
    }
  }
}
