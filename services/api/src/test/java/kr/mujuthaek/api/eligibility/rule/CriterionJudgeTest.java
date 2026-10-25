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
  void ageFromTheBirthMonth() {
    var c = new Judgment.Age(B, 19, 39);
    // 기준일 2026-10-16: 1986년 11월생은 아직 39세, 1986년 9월생은 40세
    assertThat(judge(c, applicant().birthMonth(1986, 11).build()).status()).isEqualTo(MET);
    assertThat(judge(c, applicant().birthMonth(1986, 9).build()).status()).isEqualTo(NOT_MET);
    // 생일 달이면 일을 몰라서 39세일 수도 40세일 수도 있어요
    Item birthdayMonth = judge(c, applicant().birthMonth(1986, 10).build());
    assertThat(birthdayMonth.status()).isEqualTo(UNKNOWN);
    assertThat(birthdayMonth.reason()).contains("생일");
    // 생일 달이어도 두 나이 모두 기준 안이면 충족
    assertThat(judge(c, applicant().birthMonth(1997, 10).build()).status()).isEqualTo(MET);
    Item unknown = judge(c, Applicant.empty());
    assertThat(unknown.status()).isEqualTo(UNKNOWN);
    assertThat(unknown.needs()).containsExactly("applicant.birthMonth");
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
  void homelessYearsBand() {
    var any = new Judgment.Homeless(B, "HOUSEHOLD", null);
    var threeYears = new Judgment.Homeless(B, "HOUSEHOLD", 3);
    var twoYears = new Judgment.Homeless(B, "HOUSEHOLD", 2);
    assertThat(judge(any, applicant().homelessYears("LT_1").build()).status()).isEqualTo(MET);
    assertThat(judge(any, applicant().homeOwner(true).build()).status()).isEqualTo(NOT_MET);
    assertThat(judge(any, applicant().homeOwner(false).build()).status()).isEqualTo(MET);
    assertThat(judge(threeYears, applicant().homeOwner(false).build()).status()).isEqualTo(UNKNOWN);
    // 구간 경계(1·3·5년)와 같은 기준은 정확히
    assertThat(judge(threeYears, applicant().homelessYears("3_5").build()).status()).isEqualTo(MET);
    assertThat(judge(threeYears, applicant().homelessYears("1_3").build()).status())
        .isEqualTo(NOT_MET);
    // 구간 안쪽 기준(2년)은 1~3년이면 확인 필요
    Item between = judge(twoYears, applicant().homelessYears("1_3").build());
    assertThat(between.status()).isEqualTo(UNKNOWN);
    assertThat(between.reason()).isEqualTo(Bands.BOUNDARY);
    assertThat(judge(any, Applicant.empty()).needs()).containsExactly("applicant.homelessYears");
  }

  /** 테스트용 기준 월액: 도시근로자 2025년 1인 4,000,000원, 2인 5,000,000원 · 중위 2026년 1인 2,000,000원. */
  static final IncomeBenchmarks BENCHMARKS =
      (standard, year, size) -> {
        if ("URBAN_WORKER_INCOME".equals(standard) && year == 2025) {
          return size == 1 ? 4_000_000L : size == 2 ? 5_000_000L : null;
        }
        return "MEDIAN_INCOME".equals(standard) && year == 2026 && size == 1 ? 2_000_000L : null;
      };

  static Item judgeIncome(Judgment.Income c, Applicant a) {
    return CriterionJudge.judge(c, a, REF, BENCHMARKS);
  }

  @Test
  void incomeBandAgainstThePrintedMonthlyTable() {
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
    String urban = "URBAN_WORKER_INCOME";

    // 80~85% × 4,000,000 = 3,200,000~3,400,000 ≤ 3,432,027
    Item ok = judgeIncome(c, applicant().household(1).income(urban, 80, 85).build());
    assertThat(ok.status()).isEqualTo(MET);
    assertThat(ok.threshold())
        .isEqualTo(new Item.Threshold("URBAN_WORKER_INCOME", 2025, 90.0, 1, 3_432_027L));
    assertThat(ok.label()).isEqualTo("소득 · 도시근로자 월평균소득 70% 이하");
    // 90~100% = 3,600,000~ → 넘어요
    assertThat(judgeIncome(c, applicant().household(1).income(urban, 90, 100).build()).status())
        .isEqualTo(NOT_MET);
    // 80~90% = 3,200,000~3,600,000 → 상한이 구간 안
    Item boundary = judgeIncome(c, applicant().household(1).income(urban, 80, 90).build());
    assertThat(boundary.status()).isEqualTo(UNKNOWN);
    assertThat(boundary.reason()).isEqualTo(Bands.BOUNDARY);
    assertThat(judgeIncome(c, applicant().household(2).income(urban, 0, 10).build()).needs())
        .containsExactly("applicant.householdIncome"); // 2인이면 가구 소득이 필요해요
    assertThat(
            judgeIncome(c, applicant().household(3).householdIncome(urban, 0, 10).build()).reason())
        .contains("세대원 수");
    assertThat(judgeIncome(c, applicant().income(urban, 0, 10).build()).needs())
        .containsExactly("applicant.householdSize");
  }

  @Test
  void incomeWithOnlyAPrintedTableUsesAnyBandWeCanConvert() {
    // 계약상 standard·percent가 null일 수 있어요 (공고 표만 있음). 예전엔 NPE로 맞춤 목록 전체가 500이었어요
    var c =
        new Judgment.Income(
            B,
            "HOUSEHOLD",
            null,
            2025,
            null,
            List.of(),
            "SUM",
            List.of(new Judgment.PrintedRow(1, 3_000_000)));
    Item ok =
        judgeIncome(c, applicant().household(1).income("URBAN_WORKER_INCOME", 60, 70).build());
    assertThat(ok.status()).isEqualTo(MET); // 2,400,000~2,800,000 ≤ 3,000,000
    assertThat(ok.label()).isEqualTo("소득 · 기준 소득 이하");
    // 바꿀 기준 월액이 없는 구간뿐이면 확인 필요
    assertThat(
            judgeIncome(c, applicant().household(1).income("MEDIAN_INCOME", 60, 70).build())
                .status())
        .isEqualTo(UNKNOWN);
  }

  @Test
  void incomeWithoutAPrintedTableWaitsForCriteria() {
    var c =
        new Judgment.Income(
            B, "APPLICANT", "MEDIAN_INCOME", 2026, 100.0, List.of(), "SUM", List.of());
    Item item = judge(c, applicant().household(1).income("MEDIAN_INCOME", 0, 10).build());
    assertThat(item.status()).isEqualTo(UNKNOWN);
    assertThat(item.reason()).contains("기준 금액");
  }

  @Test
  void incomeWithoutAPrintedTableUsesTheBenchmarkTimesThePercent() {
    // 기준 중위소득 100% (1인 가구 가산 +20%p → 120%), 기준 월액 2,000,000원 → 상한 2,400,000원
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
    LocalDate ref = LocalDate.of(2026, 10, 22); // 판정 틀에 연도가 없으면 기준일의 연도
    String median = "MEDIAN_INCOME";

    Item ok =
        CriterionJudge.judge(
            c, applicant().household(1).income(median, 110, 120).build(), ref, BENCHMARKS);
    Item over =
        CriterionJudge.judge(
            c, applicant().household(1).income(median, 120, 130).build(), ref, BENCHMARKS);
    Item noTable =
        CriterionJudge.judge(
            c, applicant().household(2).income(median, 0, 10).build(), ref, BENCHMARKS);
    Item noBand =
        CriterionJudge.judge(
            c,
            applicant().household(1).income("URBAN_WORKER_INCOME", 0, 10).build(),
            ref,
            BENCHMARKS);

    assertThat(ok.status()).isEqualTo(MET);
    assertThat(ok.threshold())
        .isEqualTo(new Item.Threshold("MEDIAN_INCOME", 2026, 120.0, 1, 2_400_000L));
    assertThat(over.status()).isEqualTo(NOT_MET);
    assertThat(noTable.status()).isEqualTo(UNKNOWN);
    assertThat(noBand.needs()).containsExactly("applicant.income"); // 공고 기준 종류의 구간이 없어요
  }

  @Test
  void incomeBandFromAnotherYearIsConvertedWithThatYearsBenchmark() {
    // 공고는 2025년 기준, 사용자는 2026년 기준 월액으로 % 구간을 계산했어요 (incomeYear)
    // → 2026년 기준 월액으로 금액 구간을 만들어 2025년 상한과 비교해요
    var c =
        new Judgment.Income(
            B, "APPLICANT", "URBAN_WORKER_INCOME", 2025, 100.0, List.of(), "SUM", List.of());
    IncomeBenchmarks twoYears =
        (standard, year, size) ->
            !"URBAN_WORKER_INCOME".equals(standard) || size != 1
                ? null
                : year == 2025 ? 4_000_000L : year == 2026 ? 4_400_000L : null;
    // 2026년 기준 80~90% = 3,520,000~3,960,000 ≤ 2025년 상한 4,000,000
    Item item =
        CriterionJudge.judge(
            c,
            applicant().household(1).income("URBAN_WORKER_INCOME", 80, 90).incomeYear(2026).build(),
            REF,
            twoYears);
    assertThat(item.status()).isEqualTo(MET);
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
    // 구간: 상한이 구간 안에 있으면 확인 필요, 구간이 다 넘으면 불충족
    Item boundary =
        judge(assets, applicant().assets(215_000_000L, 251_000_000L).children(0).build());
    assertThat(boundary.status()).isEqualTo(UNKNOWN);
    assertThat(boundary.reason()).isEqualTo(Bands.BOUNDARY);
    assertThat(judge(assets, applicant().assets(345_000_000L, null).children(0).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(assets, applicant().build()).needs()).containsExactly("applicant.assets");
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
    assertThat(judge(savings, applicant().subscription("24_PLUS", 26).build()).status())
        .isEqualTo(MET);
    assertThat(judge(savings, applicant().subscription("12_24", 26).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(savings, applicant().subscription("24_PLUS", 10).build()).status())
        .isEqualTo(NOT_MET);
    assertThat(judge(savings, applicant().subscription("24_PLUS", null).build()).needs())
        .containsExactly("applicant.subscription.payments");
    // 지방 6개월 기준: 6개월~1년이면 충족, 6개월 미만이면 불충족
    var sixMonths = new Judgment.Savings(B, 6, null, null, null);
    assertThat(judge(sixMonths, applicant().subscription("6_12", null).build()).status())
        .isEqualTo(MET);
    assertThat(judge(sixMonths, applicant().subscription("LT_6", null).build()).status())
        .isEqualTo(NOT_MET);
    // 구간 안쪽 기준(18개월)은 1~2년이면 확인 필요
    assertThat(
            judge(
                    new Judgment.Savings(B, 18, null, null, null),
                    applicant().subscription("12_24", null).build())
                .status())
        .isEqualTo(UNKNOWN);
    // 통장이 없으면 어떤 청약통장 조건이든 불충족
    Item none =
        judge(
            new Judgment.Savings(B, null, null, null, null),
            applicant().subscription("NONE", null).build());
    assertThat(none.status()).isEqualTo(NOT_MET);
    assertThat(none.reason()).contains("청약통장이 있어야");
    assertThat(
            judge(
                    new Judgment.Savings(B, null, null, 3_000_000L, null),
                    applicant().subscription("12_24", 1).build())
                .status())
        .isEqualTo(UNKNOWN);
    assertThat(judge(savings, Applicant.empty()).needs())
        .containsExactly("applicant.subscription.months");
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
    Item item = judge(reviewing, applicant().birthMonth(1997, 3).build());
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
