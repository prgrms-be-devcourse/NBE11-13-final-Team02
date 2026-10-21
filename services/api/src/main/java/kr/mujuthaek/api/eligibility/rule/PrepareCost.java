package kr.mujuthaek.api.eligibility.rule;

import java.time.LocalDate;
import java.util.List;

/**
 * 보증금으로 준비할 돈: 보증금 − 받을 수 있는 대출 중 가장 큰 금액.
 *
 * <p>대출 조건(소득·자산 상한)을 입력이 없어 판정할 수 없는 상품이 지금 고른 것보다 더 많이 빌려주면, 금액 대신 이유(INCOME_UNKNOWN·
 * ASSET_UNKNOWN)를 줘요. 나이·대상이 맞지 않거나 모르면 그 상품은 쓰지 않아요.
 */
public final class PrepareCost {

  private PrepareCost() {}

  /** 보증금 대출 상품 (eligibility.loan_products). 조건이 null이면 제한 없음. */
  public record LoanProduct(
      String code,
      String name,
      long maxAmountWon,
      double depositRatio,
      Long maxAnnualIncomeWon,
      Long maxTotalAssetsWon,
      Integer minAge,
      Integer maxAge,
      List<String> targetGroups) {}

  public record Loan(String productCode, String name, long amountWon) {}

  /**
   * @param prepareWon 준비할 돈 (모르면 null, 이유는 note)
   * @param shortageWon 가용 현금을 넣었을 때만, 준비할 돈보다 모자란 금액 (넉넉하면 0)
   * @param note PRICE_NOT_ANNOUNCED · INCOME_UNKNOWN · ASSET_UNKNOWN (prepareWon이 null일 때)
   */
  public record Result(
      Long depositWon, Loan loan, Long prepareWon, Long shortageWon, String note) {}

  public static Result calculate(
      Long depositWon, Applicant a, List<LoanProduct> products, LocalDate referenceDate) {
    if (depositWon == null) {
      return new Result(null, null, null, null, "PRICE_NOT_ANNOUNCED");
    }
    Loan best = null;
    long unknownBest = 0;
    String unknownNote = null;
    for (LoanProduct p : products) {
      if (!ageAndTargetFit(p, a, referenceDate)) {
        continue;
      }
      long amount = Math.min(p.maxAmountWon(), (long) Math.floor(depositWon * p.depositRatio()));
      String unknown = unknownCondition(p, a);
      if (unknown == null && !meetsMoneyLimits(p, a)) {
        continue;
      }
      if (unknown != null) {
        if (amount > unknownBest) {
          unknownBest = amount;
          unknownNote = unknown;
        }
      } else if (best == null || amount > best.amountWon()) {
        best = new Loan(p.code(), p.name(), amount);
      }
    }
    long known = best == null ? 0 : best.amountWon();
    if (unknownNote != null && unknownBest > known) {
      return new Result(depositWon, null, null, null, unknownNote);
    }
    long prepare = depositWon - known;
    Long shortage =
        a.availableCashWon() == null ? null : Math.max(0, prepare - a.availableCashWon());
    return new Result(depositWon, best, prepare, shortage, null);
  }

  private static boolean ageAndTargetFit(LoanProduct p, Applicant a, LocalDate ref) {
    if (p.minAge() != null || p.maxAge() != null) {
      if (a.birthDate() == null) {
        return false;
      }
      int age = Periods.age(a.birthDate(), ref);
      if ((p.minAge() != null && age < p.minAge()) || (p.maxAge() != null && age > p.maxAge())) {
        return false;
      }
    }
    if (p.targetGroups() != null && !p.targetGroups().isEmpty()) {
      return a.targetGroups() != null
          && a.targetGroups().stream().anyMatch(p.targetGroups()::contains);
    }
    return true;
  }

  /** 입력이 없어 판정할 수 없는 돈 조건. 다 알면 null. */
  private static String unknownCondition(LoanProduct p, Applicant a) {
    if (p.maxAnnualIncomeWon() != null && a.householdIncome() == null) {
      return "INCOME_UNKNOWN";
    }
    if (p.maxTotalAssetsWon() != null && a.totalAssetsWon() == null) {
      return "ASSET_UNKNOWN";
    }
    return null;
  }

  private static boolean meetsMoneyLimits(LoanProduct p, Applicant a) {
    return (p.maxAnnualIncomeWon() == null || a.householdIncome() <= p.maxAnnualIncomeWon())
        && (p.maxTotalAssetsWon() == null || a.totalAssetsWon() <= p.maxTotalAssetsWon());
  }
}
