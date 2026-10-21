package kr.mujuthaek.api.eligibility.controller;

import static kr.mujuthaek.api.notice.NoticeApiFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore;
import kr.mujuthaek.api.eligibility.repository.LoanProductStore;
import kr.mujuthaek.api.eligibility.service.CriteriaService;
import kr.mujuthaek.api.eligibility.service.NoticeEvaluationService;
import kr.mujuthaek.api.eligibility.service.NoticeSearchService;
import kr.mujuthaek.api.eligibility.service.PrepareCostService;
import kr.mujuthaek.api.notice.NoticeApiFixture;
import kr.mujuthaek.api.notice.NoticeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class NoticeSearchApiTest {

  private final NoticeApiFixture f = new NoticeApiFixture();
  private final CriteriaService criteria =
      new CriteriaService(new CriteriaStore(f.jdbc), NoticeFixtures.JSON, Clock.systemUTC());
  private final NoticeEvaluationService evaluations =
      new NoticeEvaluationService(
          f.queries,
          criteria,
          new PrepareCostService(new LoanProductStore(f.jdbc), Clock.systemUTC()));
  private final MockMvc mvc =
      f.mvc(new NoticeSearchController(new NoticeSearchService(f.queries, evaluations)));

  @org.junit.jupiter.api.BeforeEach
  void noLoans() {
    f.jdbc.update("delete from eligibility.loan_products"); // 대출이 없으면 준비할 돈 = 보증금
  }

  /** 나이 조건 하나짜리 판정 틀. */
  private UUID noticeForAges(int min, int max, String applyEnd) {
    return f.notice(
        p -> {
          schedule(p, "2026-10-01", applyEnd);
          p.set(
              "judgment",
              NoticeFixtures.JSON.readTree(
                  """
                  {"schema":"housing-judgment.v1","rawPostId":"x","rawVersion":1,"referenceDate":"2026-10-01",
                   "common":[],"groups":[{"id":"youth","name":"청년","criteria":[
                     {"kind":"AGE","id":"age","quote":null,"page":null,"inReview":false,"reviewReason":null,
                      "minAge":%d,"maxAge":%d}]}],"notes":[]}"""
                      .formatted(min, max)));
        });
  }

  private String body(String extra) {
    return """
        {"filters":{"providers":["%s"]},"applicant":{"birthDate":"1997-03-15"}%s}"""
        .formatted(f.provider, extra);
  }

  @Test
  void judgesEachNoticeAndPutsEligibleFirst() throws Exception {
    UUID unknown = f.notice(p -> schedule(p, "2026-10-01", "2026-10-24")); // 판정 틀 없음 → 확인 필요
    UUID eligible = noticeForAges(19, 39, "2026-10-30");
    UUID ineligible = noticeForAges(40, 60, "2026-10-25");

    var result =
        mvc.perform(
                post("/api/v1/notices/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.counts.eligible").value(1))
            .andExpect(jsonPath("$.counts.needsInput").value(1))
            .andExpect(jsonPath("$.counts.ineligible").value(1))
            .andExpect(jsonPath("$.items[0].overall").value("ELIGIBLE"))
            .andExpect(jsonPath("$.items[0].prepareCostWon").value(1000000))
            .andReturn();
    List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].id");
    assertThat(ids).containsExactly(eligible.toString(), unknown.toString());

    String all =
        mvc.perform(
                post("/api/v1/notices/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(",\"includeIneligible\":true,\"sort\":\"DEADLINE\"")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat((List<String>) JsonPath.read(all, "$.items[*].id"))
        .containsExactly(unknown.toString(), ineligible.toString(), eligible.toString());
  }

  @Test
  void pagesAndRejectsBadValues() throws Exception {
    noticeForAges(19, 39, "2026-10-30");
    noticeForAges(19, 39, "2026-10-31");

    String first =
        mvc.perform(
                post("/api/v1/notices/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(",\"size\":1")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = JsonPath.read(first, "$.nextCursor");
    mvc.perform(
            post("/api/v1/notices/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"size\":1,\"cursor\":\"" + cursor + "\"")))
        .andExpect(jsonPath("$.items[0].applyEnd").value("2026-10-31"))
        .andExpect(jsonPath("$.hasNext").value(false));

    mvc.perform(
            post("/api/v1/notices/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"sort\":\"CHEAPEST\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("sort"));
  }
}
