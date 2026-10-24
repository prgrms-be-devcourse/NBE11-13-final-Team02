package kr.mujuthaek.api.notice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import kr.mujuthaek.api.TestDatabase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class NoticeStatusTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

  @ParameterizedTest
  @CsvSource({
    ",           ,           ALWAYS,",
    "2026-10-01, 2026-10-31, APPLYING, 9",
    "2026-10-01, 2026-10-22, APPLYING, 0", // 마감일 당일
    "2026-10-23, 2026-11-05, UPCOMING, 14",
    "2026-10-01, 2026-10-21, CLOSED,",
    "2026-10-01,           , APPLYING,", // 시작만 있고 지남
    "2026-11-01,           , UPCOMING,",
    "          , 2026-10-31, APPLYING, 9", // 마감만 있음
  })
  void statusAndDday(LocalDate start, LocalDate end, NoticeStatus expected, Integer dday) {
    assertThat(NoticeStatus.of(start, end, TODAY)).isEqualTo(expected);
    assertThat(NoticeStatus.dday(end, TODAY)).isEqualTo(dday);
    assertThat(sqlStatus(start, end)).isEqualTo(expected.name());
  }

  /** 목록 필터의 SQL 식이 자바와 같은 답을 내는지. */
  private static String sqlStatus(LocalDate start, LocalDate end) {
    return new JdbcTemplate(TestDatabase.dataSource())
        .queryForObject(
            "select "
                + NoticeStatus.sql("s", "e", "t")
                + " from (select ?::date s, ?::date e, ?::date t) x",
            String.class,
            start == null ? null : Date.valueOf(start),
            end == null ? null : Date.valueOf(end),
            Date.valueOf(TODAY));
  }
}
