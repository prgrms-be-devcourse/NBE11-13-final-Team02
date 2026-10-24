package kr.mujuthaek.api.common.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장하거나 로그에 남기면 안 되는 판정 입력 칸 (소득·자산·자동차가액·매출·가용 현금·수급 여부·순위 체크·생년월일).
 *
 * <p>요청 본문은 로그에 남기지 않지만, 예외 메시지나 객체의 toString에 값이 섞여 들어갈 수 있어서 남기기 전에 {@link #mask}로 가려요. 칸을 더하면
 * 여기에 이름을 넣어요.
 */
public final class SensitiveFields {

  public static final List<String> NAMES =
      List.of(
          "personalIncomeAnnualWon",
          "householdIncomeAnnualWon",
          "totalAssetsWon",
          "carValueWon",
          "availableCashWon",
          "annualRevenueWon",
          "welfareStatuses",
          "rankChecks",
          "birthDate");

  public static final String MASK = "***";

  private static final String NAME_GROUP = "(" + String.join("|", NAMES) + ")";
  // JSON: "칸" : 값 (글자, 숫자, 배열, null)
  private static final Pattern JSON =
      Pattern.compile(
          "(\"" + NAME_GROUP + "\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\]]*\\]|[^,}\\s]+)");
  // record·toString: 칸=값 (다음 , ) ] } 전까지, 배열이면 ] 까지)
  private static final Pattern ASSIGNMENT =
      Pattern.compile("(\\b" + NAME_GROUP + "=)(\\[[^\\]]*\\]|[^,)\\]}]*)");

  private SensitiveFields() {}

  /** 민감한 칸의 값을 ***로 바꾼 글자. null이면 null. */
  public static String mask(String text) {
    if (text == null) {
      return null;
    }
    String masked = replace(JSON, text, "\"" + MASK + "\"");
    return replace(ASSIGNMENT, masked, MASK);
  }

  private static String replace(Pattern pattern, String text, String replacement) {
    Matcher m = pattern.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** 예외를 로그에 남기기 전에: 메시지(원인 포함)를 가린 같은 모양의 예외. 스택 위치는 그대로예요. */
  public static Throwable masked(Throwable t) {
    Throwable cause = t.getCause() != null && t.getCause() != t ? masked(t.getCause()) : null;
    var copy = new MaskedException(t.getClass().getName() + ": " + mask(t.getMessage()), cause);
    copy.setStackTrace(t.getStackTrace());
    return copy;
  }

  /** 가린 메시지를 담는 예외. 원래 예외 이름은 메시지 앞에 있어요. */
  static final class MaskedException extends RuntimeException {
    MaskedException(String message, Throwable cause) {
      super(message, cause, false, true);
    }

    @Override
    public String toString() {
      return getMessage();
    }
  }
}
