package kr.mujuthaek.api.eligibility.rule;

/** 공고 하나에 대한 최종 판정. 공급 대상 중 하나라도 모든 조건을 충족하면 지원 가능이에요. */
public enum Eligibility {
  ELIGIBLE,
  CHECK_NEEDED,
  INELIGIBLE;

  public static Eligibility of(Verdict verdict) {
    return switch (verdict) {
      case MET -> ELIGIBLE;
      case UNKNOWN -> CHECK_NEEDED;
      case NOT_MET -> INELIGIBLE;
    };
  }
}
