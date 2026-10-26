package kr.mujuthaek.api.eligibility.rule;

import static kr.mujuthaek.api.eligibility.rule.Status.MET;
import static kr.mujuthaek.api.eligibility.rule.Status.NOT_MET;
import static kr.mujuthaek.api.eligibility.rule.Status.UNKNOWN;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 판정 입력의 구간 값. 서버는 정확한 나이·소득·자산 대신 구간만 받아요 (개인정보 기준, 2026-10-26).
 *
 * <p>구간이 기준 안에 다 들어가면 충족, 기준을 다 넘으면 불충족, 기준이 구간 안에 걸치면 확인 필요예요.
 */
public final class Bands {

  private Bands() {}

  /** 구간이 기준 경계에 걸쳐서 판정할 수 없을 때의 이유. */
  public static final String BOUNDARY = "구간 경계라 확인이 필요해요";

  /**
   * 금액·비율 구간 (gt 초과 ~ le 이하). 한쪽이 null이면 그쪽은 열려 있어요 (gt null = 0부터).
   *
   * @param gt 이 값보다 커요 (초과)
   * @param le 이 값 이하예요
   */
  public record Range(Long gt, Long le) {

    /** 상한 이하인지: 구간이 상한 안이면 MET, 상한을 다 넘으면 NOT_MET, 걸치면 UNKNOWN. */
    public Status atMost(double limit) {
      if (le != null && le <= limit) {
        return MET;
      }
      if (gt != null && gt >= limit) {
        return NOT_MET;
      }
      return UNKNOWN;
    }

    /** 구간 × 배율 (소득 % → 연 금액 등). 원 단위 버림. */
    public Range times(double factor) {
      return new Range(
          gt == null ? null : (long) Math.floor(gt * factor),
          le == null ? null : (long) Math.floor(le * factor));
    }
  }

  /**
   * 기간 구간 (min 이상 ~ max 미만, 단위는 년 또는 개월). max가 null이면 위로 열려 있어요.
   *
   * @param none 청약통장이 아예 없으면 true (청약 가입 기간 NONE)
   */
  public record Span(int min, Integer max, boolean none) {

    /** 최소 기간 이상인지. */
    public Status atLeast(int required) {
      if (none) {
        return NOT_MET;
      }
      if (min >= required) {
        return MET;
      }
      if (max != null && max <= required) {
        return NOT_MET;
      }
      return UNKNOWN;
    }
  }

  /** 무주택 기간 (년): LT_1 · 1_3 · 3_5 · 5_PLUS. */
  public static final Map<String, Span> HOMELESS_YEARS =
      Map.of(
          "LT_1", new Span(0, 1, false),
          "1_3", new Span(1, 3, false),
          "3_5", new Span(3, 5, false),
          "5_PLUS", new Span(5, null, false));

  /** 청약통장 가입 기간 (개월): NONE · LT_6 · 6_12 · 12_24 · 24_PLUS. */
  public static final Map<String, Span> SUBSCRIPTION_MONTHS =
      Map.of(
          "NONE", new Span(0, 0, true),
          "LT_6", new Span(0, 6, false),
          "6_12", new Span(6, 12, false),
          "12_24", new Span(12, 24, false),
          "24_PLUS", new Span(24, null, false));

  /** 출생 연월로 본 기준일의 만 나이 범위 [어릴 때, 많을 때]. 기준일이 생일 달이면 두 값이 달라요. */
  public static int[] ages(YearMonth birthMonth, LocalDate referenceDate) {
    int youngest = Periods.age(birthMonth.atEndOfMonth(), referenceDate);
    int oldest = Periods.age(birthMonth.atDay(1), referenceDate);
    return new int[] {youngest, oldest};
  }

  /** 나이 범위가 기준(min~max) 안인지. */
  public static Status ageWithin(int[] ages, Integer min, Integer max) {
    boolean young = fits(ages[0], min, max);
    boolean old = fits(ages[1], min, max);
    if (young && old) {
      return MET;
    }
    if (!young && !old) {
      return NOT_MET;
    }
    return UNKNOWN;
  }

  private static boolean fits(int age, Integer min, Integer max) {
    return (min == null || age >= min) && (max == null || age <= max);
  }
}
