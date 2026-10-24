package kr.mujuthaek.api.notice;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujuthaek.api.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NoticeTablesTest {

  private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());

  @Test
  void createsNoticeTables() {
    assertThat(
            jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'notice' order by 1",
                String.class))
        .contains(
            "extraction_results",
            "notice_follow_ups",
            "notice_posts",
            "notice_versions",
            "notices");
  }
}
