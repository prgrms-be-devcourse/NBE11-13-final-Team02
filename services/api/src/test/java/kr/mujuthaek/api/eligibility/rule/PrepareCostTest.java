package kr.mujuthaek.api.eligibility.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kr.mujuthaek.api.eligibility.rule.PrepareCost.LoanProduct;
import org.junit.jupiter.api.Test;

/** 상품 조건은 테스트용 값이에요 (실제 한도는 관리자가 넣어요). */
class PrepareCostTest {

  private static final LocalDate REF = LocalDate.of(2026, 10, 22);
  private static final LoanProduct YOUTH =
      new LoanProduct(
          "YOUTH", "청년 전세자금", 200_000_000, 0.8, 50_000_000L, 340_000_000L, 19, 34, List.of());
  private static final LoanProduct GENERAL =
      new LoanProduct("GENERAL", "일반 전세자금", 120_000_000, 0.7, null, null, null, null, List.of());

  @Test
  void picksTheLargestLoanTheApplicantQualifiesFor() {
    var a =
        new ApplicantBuilder()
            .birth(1997, 3, 15)
            .household(1)
            .personalIncome(32_000_000L)
            .assets(45_000_000L)
            .cash(5_000_000L)
            .build();

    var r = PrepareCost.calculate(24_000_000L, a, List.of(GENERAL, YOUTH), REF);

    assertThat(r.loan()).isEqualTo(new PrepareCost.Loan("YOUTH", "청년 전세자금", 19_200_000));
    assertThat(r.prepareWon()).isEqualTo(4_800_000);
    assertThat(r.shortageWon()).isZero();
    assertThat(r.note()).isNull();
  }

  @Test
  void unknownIncomeThatCouldGiveMoreMeansNoAmountYet() {
    var a = new ApplicantBuilder().birth(1997, 3, 15).build();

    var r = PrepareCost.calculate(24_000_000L, a, List.of(GENERAL, YOUTH), REF);

    assertThat(r.prepareWon()).isNull();
    assertThat(r.note()).isEqualTo("INCOME_UNKNOWN");
  }

  @Test
  void tooOldOrOverTheIncomeLimitFallsBackToTheGeneralLoan() {
    var old = new ApplicantBuilder().birth(1980, 1, 1).cash(1_000_000L).build();
    var rich =
        new ApplicantBuilder()
            .birth(1997, 3, 15)
            .household(1)
            .personalIncome(90_000_000L)
            .assets(1L)
            .build();

    var r = PrepareCost.calculate(24_000_000L, old, List.of(GENERAL, YOUTH), REF);
    assertThat(r.loan().productCode()).isEqualTo("GENERAL");
    assertThat(r.prepareWon()).isEqualTo(24_000_000 - 16_800_000);
    assertThat(r.shortageWon()).isEqualTo(7_200_000 - 1_000_000);
    assertThat(
            PrepareCost.calculate(24_000_000L, rich, List.of(GENERAL, YOUTH), REF)
                .loan()
                .productCode())
        .isEqualTo("GENERAL");
  }

  @Test
  void noProductsOrNoPrice() {
    var a = Applicant.empty();
    assertThat(PrepareCost.calculate(10_000_000L, a, List.of(), REF).prepareWon())
        .isEqualTo(10_000_000);
    assertThat(PrepareCost.calculate(null, a, List.of(GENERAL), REF).note())
        .isEqualTo("PRICE_NOT_ANNOUNCED");
    var newlyweds =
        new LoanProduct(
            "NEWLY", "신혼", 300_000_000, 0.8, null, null, null, null, List.of("NEWLYWED"));
    assertThat(PrepareCost.calculate(10_000_000L, a, List.of(newlyweds), REF).loan()).isNull();
  }
}
