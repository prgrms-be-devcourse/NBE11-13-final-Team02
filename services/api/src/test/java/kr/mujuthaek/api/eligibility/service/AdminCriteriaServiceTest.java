package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.mujuthaek.api.common.exception.CustomException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 관리자 기준값 입력의 모양 검사. */
class AdminCriteriaServiceTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void incomeNeedsPositiveWonFromOnePersonHousehold() {
    assertThatCode(
            () ->
                AdminCriteriaService.checkData(
                    "MEDIAN_INCOME",
                    json.readTree("{\"monthlyWon\":{\"1\":2564238,\"2\":4199292}}")))
        .doesNotThrowAnyException();
    for (String bad :
        new String[] {
          "{}",
          "{\"monthlyWon\":{}}",
          "{\"monthlyWon\":{\"2\":4199292}}",
          "{\"monthlyWon\":{\"1\":0}}",
          "{\"monthlyWon\":{\"1\":\"2564238\"}}",
          "{\"monthlyWon\":{\"1\":2564238.5}}",
          "{\"monthlyWon\":{\"1\":2564238,\"x\":1}}",
          "[1]"
        }) {
      assertThatThrownBy(
              () -> AdminCriteriaService.checkData("URBAN_WORKER_INCOME", json.readTree(bad)))
          .as(bad)
          .isInstanceOf(CustomException.class);
    }
  }

  @Test
  void rankCriteriaNeedsRanksOrByNoticeType() {
    assertThatCode(
            () -> AdminCriteriaService.checkData("RANK_CRITERIA", json.readTree("{\"ranks\":[]}")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                AdminCriteriaService.checkData(
                    "RANK_CRITERIA", json.readTree("{\"byNoticeType\":{}}")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> AdminCriteriaService.checkData("RANK_CRITERIA", json.readTree("{\"x\":1}")))
        .isInstanceOf(CustomException.class);
  }
}
