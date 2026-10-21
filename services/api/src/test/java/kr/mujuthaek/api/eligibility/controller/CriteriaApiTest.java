package kr.mujuthaek.api.eligibility.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.common.exception.GlobalExceptionHandlerAccess;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore;
import kr.mujuthaek.api.eligibility.service.CriteriaService;
import kr.mujuthaek.api.notice.NoticeFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 금액은 테스트용 값이에요 (실제 값은 그해 고시로 관리자가 넣어요). */
class CriteriaApiTest {

  private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());
  private final ZoneId seoul = ZoneId.of("Asia/Seoul");
  private final CriteriaService service =
      new CriteriaService(
          new CriteriaStore(jdbc),
          NoticeFixtures.JSON,
          Clock.fixed(LocalDate.of(2026, 10, 22).atStartOfDay(seoul).toInstant(), seoul));
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new EligibilityController(service))
          .setControllerAdvice(GlobalExceptionHandlerAccess.handler())
          .build();

  @BeforeEach
  void seed() {
    jdbc.update("delete from eligibility.criteria_sets");
    jdbc.update(
        "insert into eligibility.criteria_sets (kind, year, valid_from, data) values"
            + " ('MEDIAN_INCOME', 2026, '2026-01-01', '{\"monthlyWon\": {\"1\": 1000001, \"2\": 1600002}}'),"
            + " ('RANK_CRITERIA', 2026, '2026-01-01', '{\"ranks\": [{\"rank\": 2, \"rule\": \"ALL\","
            + " \"items\": [{\"key\": \"R2_ASSET\", \"label\": \"총자산가액 기준 이하\", \"limitWon\": 1}]}],"
            + " \"byNoticeType\": {\"HAPPY_HOUSING\": [{\"rank\": 1, \"rule\": \"ANY\", \"items\": []}]}}')");
  }

  @Test
  void criteriaForTheLatestYearInUse() throws Exception {
    mvc.perform(get("/api/v1/eligibility/criteria"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=86400, public"))
        .andExpect(jsonPath("$.year").value(2026))
        .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
        .andExpect(jsonPath("$.medianIncomeMonthlyWon.1").value(1000001))
        .andExpect(jsonPath("$.rankCriteria[0].items[0].key").value("R2_ASSET"));
  }

  @Test
  void noticeTypeSpecificRanks() throws Exception {
    mvc.perform(
            get("/api/v1/eligibility/criteria")
                .param("year", "2026")
                .param("noticeType", "HAPPY_HOUSING"))
        .andExpect(jsonPath("$.rankCriteria[0].rank").value(1));
  }

  @Test
  void yearWithoutCriteriaIsNotFound() throws Exception {
    mvc.perform(get("/api/v1/eligibility/criteria").param("year", "2019"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CRITERIA_NOT_FOUND"));
  }

  @Test
  void monthlyIncomeForJudging() {
    org.assertj.core.api.Assertions.assertThat(service.monthlyIncome("MEDIAN_INCOME", 2026))
        .containsEntry("2", 1600002L);
    org.assertj.core.api.Assertions.assertThat(service.monthlyIncome("URBAN_WORKER_INCOME", 2026))
        .isEmpty();
  }
}
