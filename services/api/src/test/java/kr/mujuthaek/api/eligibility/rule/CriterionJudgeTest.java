package kr.mujuthaek.api.eligibility.rule;

import static kr.mujuthaek.api.eligibility.rule.Status.MET;
import static kr.mujuthaek.api.eligibility.rule.Status.NOT_MET;
import static kr.mujuthaek.api.eligibility.rule.Status.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kr.mujuthaek.api.eligibility.rule.Judgment.Basics;
import kr.mujuthaek.api.eligibility.rule.Judgment.Criterion;
import kr.mujuthaek.api.eligibility.rule.Judgment.Factor;
import org.junit.jupiter.api.Test;

class CriterionJudgeTest {

  static final LocalDate REF = LocalDate.of(2026, 10, 16);
  static final Basics B = new Basics("c1", "원문", 3, false, null);

  static ApplicantBuilder applicant() {
    return new ApplicantBuilder();
  }

  static Item judge(Criterion c, Applicant a) {
    return CriterionJudge.judge(c, a, REF);
  }

  @Test
  void age() {
    var c = new Judgment.Age(B, 19, 39);
    assertThat(judge(c, applicant().birth(1986, 10, 17).build()).status()).isEqualTo(MET);
    assertThat(judge(c, applicant().birth(1986, 10, 16).build()).status()).isEqualTo(NOT_MET);
    Item unknown = judge(c, Applicant.empty());
    assertThat(unknown.status()).isEqualTo(UNKNOWN);
    assertThat(unknown.needs()).containsExactly("applicant.birthDate");
    assertThat(unknown.label()).isEqualTo("나이 · 만 19~39세");
    assertThat(unknown.evidence()).isEqualTo(new Item.Evidence("원문", 3));
  }

  @Test
  void residenceComparesTheMeaningfulPartOfTheRegionCode() {
    var seoul = new Judgment.Residence(B, List.of("1100000000"), null);
    assertThat(judge(seoul, applicant().region("11440").build()).status()).isEqualTo(MET);
    assertThat(judge(seoul, applicant().region("41111").build()).status()).isEqualTo(NOT_MET);
    var dong = new Judgment.Residence(B, List.of("1144012000"), null);
    assertThat(judge(dong, applicant().region("11440").build()).status()).isEqualTo(UNKNOWN);
    var withMonths = new Judgment.Residence(B, List.of("1100000000"), 12);
    assertThat(judge(withMonths, applicant().region("11440").build()).status()).isEqualTo(UNKNOWN);
    assertThat(judge(withMonths, applicant().region("41111").build()).status()).isEqualTo(NOT_MET);
  }

  @Test
  void homeless() {
    var any = new Judgment.Homeless(B, "HOUSEHOLD", null);
    var twoYears = new Judgment.Homeless(B, "HOUSEHOLD", 2);
    assertThat(judge(any, applicant().homelessSince(2022, 1).build()).status()).isEqualTo(MET);
    assertThat(judge(any, applicant().homeOwner(true).build()).status()).isEqualTo(NOT_MET);
    assertThat(judge(any, applicant().homeOwner(false).build()).status()).isEqualTo(MET);
    assertThat(judge(twoYears, applicant().homeOwner(false).build()).status()).isEqualTo(UNKNOWN);
    assertThat(judge(twoYears, applicant().homelessSince(2025, 1).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(any, Applicant.empty()).needs()).containsExactly("applicant.homelessSince");
  }

  @Test
  void incomeUsesThePrintedMonthlyTableWithoutEchoingTheIncome() {
    var c =
        new Judgment.Income(
            B,
            "HOUSEHOLD",
            "URBAN_WORKER_INCOME",
            2025,
            70.0,
            List.of(new Judgment.Bonus(new Factor("HOUSEHOLD_SIZE", 1, 1), 20)),
            "SUM",
            List.of(new Judgment.PrintedRow(1, 3_432_027), new Judgment.PrintedRow(2, 4_693_016)));

    Item ok = judge(c, applicant().household(1).personalIncome(3_432_027L * 12).build());
    assertThat(ok.status()).isEqualTo(MET);
    assertThat(ok.threshold())
        .isEqualTo(new Item.Threshold("URBAN_WORKER_INCOME", 2025, 90.0, 1, 3_432_027L));
    assertThat(ok.label()).isEqualTo("소득 · 도시근로자 월평균소득 70% 이하");
    assertThat(
            judge(c, applicant().household(1).personalIncome(3_432_027L * 12 + 1).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(c, applicant().household(2).personalIncome(1L).build()).needs())
        .containsExactly("applicant.householdIncomeAnnualWon"); // 2인이면 가구 소득이 필요해요
    assertThat(judge(c, applicant().household(3).householdIncome(1L).build()).reason())
        .contains("세대원 수");
    assertThat(judge(c, applicant().personalIncome(1L).build()).needs())
        .containsExactly("applicant.householdSize");
  }

  @Test
  void incomeWithOnlyAPrintedTableHasNoStandard() {
    // 계약상 standard·percent가 null일 수 있어요 (공고 표만 있음). 예전엔 NPE로 맞춤 목록 전체가 500이었어요
    var c =
        new Judgment.Income(
            B,
            "HOUSEHOLD",
            null,
            null,
            null,
            List.of(),
            "SUM",
            List.of(new Judgment.PrintedRow(1, 3_000_000)));
    Item ok = judge(c, applicant().household(1).personalIncome(3_000_000L * 12).build());
    assertThat(ok.status()).isEqualTo(MET);
    assertThat(ok.label()).isEqualTo("소득 · 기준 소득 이하");
  }

  @Test
  void incomeWithoutAPrintedTableWaitsForCriteria() {
    var c =
        new Judgment.Income(
            B, "APPLICANT", "MEDIAN_INCOME", 2026, 100.0, List.of(), "SUM", List.of());
    Item item = judge(c, applicant().household(1).personalIncome(1L).build());
    assertThat(item.status()).isEqualTo(UNKNOWN);
    assertThat(item.reason()).contains("기준 금액");
  }

  @Test
  void incomeWithoutAPrintedTableUsesTheBenchmarkTimesThePercent() {
    // 기준 중위소득 100% (1인 가구 가산 +20%p → 120%), 테스트용 기준 월액 2,000,000원 → 상한 2,400,000원
    var c =
        new Judgment.Income(
            B,
            "APPLICANT",
            "MEDIAN_INCOME",
            null,
            100.0,
            List.of(new Judgment.Bonus(new Factor("HOUSEHOLD_SIZE", 1, 1), 20)),
            "SUM",
            List.of());
    IncomeBenchmarks benchmarks =
        (standard, year, size) ->
            "MEDIAN_INCOME".equals(standard) && year == 2026 && size == 1 ? 2_000_000L : null;
    LocalDate ref = LocalDate.of(2026, 10, 22); // 판정 틀에 연도가 없으면 기준일의 연도

    Item ok =
        CriterionJudge.judge(
            c, applicant().household(1).personalIncome(28_800_000L).build(), ref, benchmarks);
    Item over =
        CriterionJudge.judge(
            c, applicant().household(1).personalIncome(28_800_001L).build(), ref, benchmarks);
    Item noTable =
        CriterionJudge.judge(
            c, applicant().household(2).personalIncome(1L).build(), ref, benchmarks);

    assertThat(ok.status()).isEqualTo(MET);
    assertThat(ok.threshold())
        .isEqualTo(new Item.Threshold("MEDIAN_INCOME", 2026, 120.0, 1, 2_400_000L));
    assertThat(over.status()).isEqualTo(NOT_MET);
    assertThat(noTable.status()).isEqualTo(UNKNOWN);
  }

  @Test
  void assetsAndCarsWithOverrides() {
    var assets =
        new Judgment.Asset(
            B,
            "HOUSEHOLD",
            245_000_000,
            List.of(new Judgment.Override(new Factor("CHILDREN", 2, null), 300_000_000)));
    assertThat(judge(assets, applicant().assets(245_000_000L).build()).status()).isEqualTo(MET);
    assertThat(judge(assets, applicant().assets(250_000_000L).children(2).build()).status())
        .isEqualTo(MET);
    assertThat(judge(assets, applicant().assets(250_000_000L).children(0).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(assets, applicant().assets(250_000_000L).build()).status()).isEqualTo(UNKNOWN);
    assertThat(judge(assets, applicant().build()).label()).isEqualTo("총자산 · 2억 4,500만 원 이하");

    var car = new Judgment.Car(B, 45_420_000, List.of());
    Item carItem = judge(car, applicant().car(45_420_001L).build());
    assertThat(carItem.key()).isEqualTo("CAR_VALUE");
    assertThat(carItem.status()).isEqualTo(NOT_MET);
    assertThat(carItem.label()).isEqualTo("자동차가액 · 4,542만 원 이하");
  }

  @Test
  void marriage() {
    var newlywed = new Judgment.Marriage(B, "MARRIED", 7, true);
    assertThat(judge(newlywed, applicant().married(2020, 1).build()).status()).isEqualTo(MET);
    assertThat(judge(newlywed, applicant().married(2019, 10).build()).status()).isEqualTo(NOT_MET);
    assertThat(judge(newlywed, applicant().marital("ENGAGED").build()).status()).isEqualTo(MET);
    assertThat(judge(newlywed, applicant().marital("SINGLE").build()).status()).isEqualTo(NOT_MET);
    assertThat(judge(newlywed, applicant().marital("MARRIED").build()).needs())
        .containsExactly("applicant.marriedOn");
    var single = new Judgment.Marriage(B, "NOT_MARRIED", null, false);
    assertThat(judge(single, applicant().marital("SINGLE").build()).status()).isEqualTo(MET);
    assertThat(judge(single, applicant().married(2020, 1).build()).status()).isEqualTo(NOT_MET);
  }

  @Test
  void childrenAndSavings() {
    var two = new Judgment.Children(B, 2, null, false);
    assertThat(judge(two, applicant().children(3).build()).status()).isEqualTo(MET);
    assertThat(judge(two, applicant().children(1).build()).status()).isEqualTo(NOT_MET);
    assertThat(
            judge(new Judgment.Children(B, 2, null, true), applicant().children(1).build())
                .status())
        .isEqualTo(UNKNOWN);
    assertThat(
            judge(new Judgment.Children(B, 1, 6, false), applicant().children(1).build()).status())
        .isEqualTo(UNKNOWN);

    var savings = new Judgment.Savings(B, 24, 24, null, null);
    assertThat(judge(savings, applicant().subscription(2024, 3, 26).build()).status())
        .isEqualTo(MET);
    assertThat(judge(savings, applicant().subscription(2025, 3, 26).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(savings, applicant().subscription(2024, 3, 10).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(
            judge(
                    new Judgment.Savings(B, null, null, 3_000_000L, null),
                    applicant().subscription(2024, 3, 1).build())
                .status())
        .isEqualTo(UNKNOWN);
  }

  @Test
  void welfareStatuses() {
    var c = new Judgment.WelfareStatus(B, List.of("LIVELIHOOD_BENEFIT", "NEAR_POOR"));
    assertThat(judge(c, applicant().welfare("NEAR_POOR").build()).status()).isEqualTo(MET);
    assertThat(judge(c, applicant().welfare().build()).status()).isEqualTo(NOT_MET);
    assertThat(judge(c, Applicant.empty()).status()).isEqualTo(UNKNOWN);
    var disabled = new Judgment.WelfareStatus(B, List.of("DISABLED", "NEAR_POOR"));
    assertThat(judge(disabled, applicant().welfare().build()).status()).isEqualTo(UNKNOWN);
    var student = new Judgment.WelfareStatus(B, List.of("UNIVERSITY_STUDENT"));
    assertThat(judge(student, applicant().targetGroups("UNIVERSITY_STUDENT").build()).status())
        .isEqualTo(MET);
  }

  @Test
  void askAndInReviewAreAlwaysToBeChecked() {
    Item ask = judge(new Judgment.Ask(B, "예술인임을 증명할 수 있나요?", "YES"), Applicant.empty());
    assertThat(ask.status()).isEqualTo(UNKNOWN);
    assertThat(ask.reason()).isEqualTo("예술인임을 증명할 수 있나요?");

    var reviewing = new Judgment.Age(new Basics("c2", null, null, true, "나이 표기가 두 가지"), 19, 39);
    Item item = judge(reviewing, applicant().birth(1997, 3, 15).build());
    assertThat(item.status()).isEqualTo(UNKNOWN);
    assertThat(item.reason()).contains("확인하는 중", "나이 표기가 두 가지");
    assertThat(item.evidence()).isNull();
  }

  @Test
  void regionPrefix() {
    assertThat(CriterionJudge.regionPrefix("1100000000")).isEqualTo("11");
    assertThat(CriterionJudge.regionPrefix("1144000000")).isEqualTo("11440");
    assertThat(CriterionJudge.regionPrefix("1144012000")).isEqualTo("11440120");
    assertThat(CriterionJudge.regionPrefix("1144012012")).isEqualTo("1144012012");
  }

  @Test
  void wonLabels() {
    assertThat(Won.format(245_000_000)).isEqualTo("2억 4,500만 원");
    assertThat(Won.format(45_420_000)).isEqualTo("4,542만 원");
    assertThat(Won.format(2_669_354)).isEqualTo("266만 9,354원");
    assertThat(Won.format(0)).isEqualTo("0원");
  }
}
