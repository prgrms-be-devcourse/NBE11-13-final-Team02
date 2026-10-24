package kr.mujuthaek.api.eligibility.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 보증금으로 준비할 돈: 보증금 − 받을 수 있는 대출 중 가장 큰 금액.
 *
 * <p>아는 소득·자산이 상한을 넘는 상품은 먼저 빼요. 남은 상품 중 입력이 없어 소득·자산 조건을 판정할 수 없는 상품이 지금 고른 것보다 더 많이 빌려주면, 금액 대신
 * 이유(INCOME_UNKNOWN·ASSET_UNKNOWN)를 줘요. 나이·대상이 맞지 않거나 모르면 그 상품은 쓰지 않아요.
 */
public final class PrepareCost {

  private PrepareCost() {}

  /** 보증금 대출 상품 (eligibility.loan_products). 조건이 null이면 제한 없음. */
  public record LoanProduct(
      String code,
      String name,
      long maxAmountWon,
      BigDecimal depositRatio,
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
      if (exceedsKnownLimit(p, a)) { // 아는 값만으로 이미 못 받는 상품
        continue;
      }
      long amount = Math.min(p.maxAmountWon(), loanAmount(depositWon, p.depositRatio()));
      String unknown = unknownCondition(p, a);
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

  /** 보증금 × 비율, 원 단위 버림. double로 곱하면 0.29 같은 비율에서 1원 모자라게 나와서 BigDecimal로 계산해요. */
  static long loanAmount(long depositWon, BigDecimal ratio) {
    return BigDecimal.valueOf(depositWon)
        .multiply(ratio)
        .setScale(0, RoundingMode.FLOOR)
        .longValueExact();
  }

  /** 아는 소득·자산이 상한을 넘으면 true. 모르는 값은 여기서 보지 않아요. */
  private static boolean exceedsKnownLimit(LoanProduct p, Applicant a) {
    Long income = a.householdIncome();
    Long assets = a.totalAssetsWon();
    return (p.maxAnnualIncomeWon() != null && income != null && income > p.maxAnnualIncomeWon())
        || (p.maxTotalAssetsWon() != null && assets != null && assets > p.maxTotalAssetsWon());
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
}
