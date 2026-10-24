package kr.mujuthaek.api.notice.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 접수 상태 (API 설계 "이번에 정하는 코드"). 날짜가 지나면 바뀌어서 저장하지 않고 조회할 때 오늘 날짜로 계산해요.
 *
 * <ul>
 *   <li>ALWAYS 상시: 접수 시작·마감이 둘 다 없음
 *   <li>CLOSED 마감: 마감일이 지남
 *   <li>UPCOMING 접수예정: 시작일 전
 *   <li>APPLYING 접수중: 그 밖 (시작일만 있고 지났거나, 기간 안)
 * </ul>
 *
 * 목록 필터는 같은 규칙의 SQL 식({@link #sql})을 써요.
 */
public enum NoticeStatus {
  UPCOMING,
  APPLYING,
  CLOSED,
  ALWAYS;

  public static NoticeStatus of(LocalDate applyStart, LocalDate applyEnd, LocalDate today) {
    if (applyStart == null && applyEnd == null) {
      return ALWAYS;
    }
    if (applyEnd != null && today.isAfter(applyEnd)) {
      return CLOSED;
    }
    if (applyStart != null && today.isBefore(applyStart)) {
      return UPCOMING;
    }
    return APPLYING;
  }

  /** 마감까지 남은 날 (마감일 당일 0). 마감일이 없거나 지났으면 null. */
  public static Integer dday(LocalDate applyEnd, LocalDate today) {
    if (applyEnd == null || today.isAfter(applyEnd)) {
      return null;
    }
    return (int) ChronoUnit.DAYS.between(today, applyEnd);
  }

  /** 같은 규칙의 SQL 식. start·end는 칸 이름, today는 자리표시(?)나 날짜 식이에요. */
  public static String sql(String start, String end, String today) {
    return "case when "
        + start
        + " is null and "
        + end
        + " is null then 'ALWAYS' when "
        + end
        + " < "
        + today
        + " then 'CLOSED' when "
        + start
        + " > "
        + today
        + " then 'UPCOMING' else 'APPLYING' end";
  }
}
