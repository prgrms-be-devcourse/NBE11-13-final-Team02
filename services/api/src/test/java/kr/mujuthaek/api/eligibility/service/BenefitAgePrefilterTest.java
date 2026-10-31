package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import kr.mujuthaek.api.benefit.BenefitFixture;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore;
import kr.mujuthaek.api.eligibility.repository.LoanProductStore;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.eligibility.rule.ApplicantBuilder;
import kr.mujuthaek.api.eligibility.rule.Bands;
import kr.mujuthaek.api.eligibility.rule.Overall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 혜택 맞춤 목록이 나이로 지원 불가가 확실한 혜택을 DB에서 미리 골라도, 모든 혜택을 읽어 판정한 것과 결과가 같은지. 오늘은 2026-10-22 고정, 지역은 테스트마다
 * 다른 시군구라 다른 테스트의 혜택과 섞이지 않아요.
 */
class BenefitAgePrefilterTest {

  private final BenefitFixture f = new BenefitFixture();
  private final CriteriaService criteria =
      new CriteriaService(
          new CriteriaStore(f.jdbc), new LoanProductStore(f.jdbc), BenefitFixture.JSON, f.clock);
  private final BenefitEvaluationService evaluations =
      new BenefitEvaluationService(f.queries, criteria, BenefitFixture.JSON, f.clock);
  private final String region = "4" + (int) (Math.random() * 9000 + 1000);

  @Test
  void prefilteredJudgmentIsTheSameAsJudgingEveryBenefit() {
    seedBenefits();
    List<YearMonth> births =
        List.of(
            YearMonth.of(1997, 3), // 29세
            YearMonth.of(2003, 11), // 22세
            YearMonth.of(1958, 4), // 68세
            YearMonth.of(1986, 10), // 39·40세 경계 (그달 1일생 40, 말일생 39)
            YearMonth.of(1991, 6));
    int ruledOut = 0;
    for (YearMonth birth : births) {
      Applicant applicant =
          new ApplicantBuilder().birthMonth(birth.getYear(), birth.getMonthValue()).build();
      assertThat(prefiltered(applicant)).as("출생 %s", birth).isEqualTo(judgedOneByOne(applicant));
      ruledOut +=
          f.queries
              .openForJudgment(List.of(region), Bands.ages(birth, BenefitFixture.TODAY))
              .ruledOutByAge()
              .size();
    }
    assertThat(ruledOut).as("나이로 실제로 걸러야 시험이 의미 있어요").isGreaterThan(0);

    // 출생 연월이 없으면 거르지 않아요
    Applicant noBirth = new ApplicantBuilder().build();
    assertThat(prefiltered(noBirth)).isEqualTo(judgedOneByOne(noBirth));
    assertThat(f.queries.openForJudgment(List.of(region), null).ruledOutByAge()).isEmpty();
  }

  @Test
  void ruledOutBenefitsAreStillCountedAsIneligibleInTheList() {
    UUID young = benefit(c -> age(c, 1, 19, 39, true));
    UUID old = benefit(c -> age(c, 1, 65, null, true));
    var search = new BenefitSearchService(evaluations);
    var request =
        BenefitFixture.JSON.readValue(
            """
            {"filters":{"regionCodes":["%s"]},"applicant":{"birthMonth":"1997-03"},"size":20}"""
                .formatted(region),
            kr.mujuthaek.api.eligibility.dto.request.BenefitSearchRequest.class);

    var result = search.search(request);

    assertThat(result.summary().ineligible()).isEqualTo(1);
    assertThat(result.items()).extracting(i -> i.id()).containsExactly(young).doesNotContain(old);
  }

  private Map<UUID, Overall> prefiltered(Applicant applicant) {
    Map<UUID, Overall> out = new LinkedHashMap<>();
    evaluations
        .judgeAll(List.of(region), applicant)
        .forEach(j -> out.put(j.benefit().id(), j.result().overall()));
    return out;
  }

  /** 예전처럼 모든 혜택의 조건을 읽어 하나씩 판정 */
  private Map<UUID, Overall> judgedOneByOne(Applicant applicant) {
    var judge = evaluations.evaluator(applicant);
    Map<UUID, Overall> out = new LinkedHashMap<>();
    f.queries.openForJudgment(List.of(region)).stream()
        .map(judge)
        .forEach(j -> out.put(j.benefit().id(), j.result().overall()));
    return out;
  }

  private void seedBenefits() {
    benefit(c -> age(c, 1, 19, 39, true)); // 청년
    benefit(c -> age(c, 1, 40, 60, true)); // 중장년
    benefit(c -> age(c, 1, 65, null, true)); // 노인 (min만)
    benefit(c -> age(c, 1, null, 34, true)); // max만
    benefit(c -> age(c, 1, 19, 39, false)); // 값을 모르는 나이 → 확인 필요
    benefit(c -> ageRaw(c, 1, "{\"min\": 19.0, \"max\": 39.9}")); // 소수는 정수로 버려요
    benefit(c -> ageRaw(c, 1, "{\"min\": \"19\", \"max\": \"24\"}")); // 문자는 값이 없는 것
    benefit(c -> ageRaw(c, 1, "{}"));
    benefit(
        c -> { // 같은 그룹의 다른 조건과 「또는」
          age(c, 1, 40, 60, true);
          other(c, 1, "MARITAL", "{\"in\": [\"SINGLE\"]}");
        });
    benefit(
        c -> { // 같은 그룹의 나이 둘 (19~24 또는 30~35)
          age(c, 1, 19, 24, true);
          age(c, 1, 30, 35, true);
        });
    benefit(
        c -> { // 다른 그룹(그리고)과 함께
          other(c, 1, "MARITAL", "{\"in\": [\"SINGLE\"]}");
          age(c, 2, 40, 60, true);
        });
    benefit(
        c -> { // 안내 조건은 판정에서 빠져요
          other(c, 1, "EXCLUSIVE", null);
          age(c, 1, 40, 60, true);
        });
    benefit(c -> other(c, 1, "EXCLUSIVE", null)); // 안내 조건만
    benefit(c -> {}); // 조건 없음
    benefit(
        c -> age(c, 1, 40, 60, true),
        p -> p.withObject("quality").put("needsReview", true)); // 검수 중이어도 지원 불가는 지원 불가
  }

  private UUID benefit(Consumer<ArrayNode> conditions) {
    return benefit(conditions, p -> {});
  }

  private UUID benefit(Consumer<ArrayNode> conditions, Consumer<ObjectNode> more) {
    return f.benefit(
        p -> {
          var source = p.withObject("source");
          source.put("regionScope", "SIGUNGU");
          source.putArray("regionCodes").add(region);
          conditions.accept(p.putArray("conditions"));
          more.accept(p);
        });
  }

  private static void age(ArrayNode c, int group, Integer min, Integer max, boolean known) {
    ObjectNode value = BenefitFixture.JSON.createObjectNode();
    if (min != null) value.put("min", min);
    if (max != null) value.put("max", max);
    line(c, group, "AGE", known ? value : null, known);
  }

  private static void ageRaw(ArrayNode c, int group, String value) {
    line(c, group, "AGE", (ObjectNode) BenefitFixture.JSON.readTree(value), true);
  }

  private static void other(ArrayNode c, int group, String type, String value) {
    line(
        c,
        group,
        type,
        value == null ? null : (ObjectNode) BenefitFixture.JSON.readTree(value),
        value != null);
  }

  private static void line(ArrayNode c, int group, String type, ObjectNode value, boolean known) {
    ObjectNode line =
        c.addObject()
            .put("type", type)
            .put("group", group)
            .put("known", known)
            .put("origin", "RULE")
            .put("rawText", type + " " + group)
            .put("evidence", type)
            .put("confidence", 1);
    if (value == null) line.putNull("value");
    else line.set("value", value);
  }
}
