package kr.mujuthaek.api.eligibility.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** eligibility.criteria_sets 읽기. 값은 jsonb 그대로 돌려주고 해석은 서비스가 해요. */
@Repository
public class CriteriaStore {

  private final JdbcTemplate jdbc;

  public CriteriaStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record CriteriaRow(String kind, int year, LocalDate validFrom, String data) {}

  public Optional<CriteriaRow> find(String kind, int year) {
    return jdbc
        .query(
            "select kind, year, valid_from, data::text from eligibility.criteria_sets where kind = ? and year = ?",
            (rs, i) ->
                new CriteriaRow(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getObject(3, LocalDate.class),
                    rs.getString(4)),
            kind,
            year)
        .stream()
        .findFirst();
  }

  /** 그날 쓰는 값이 있는 가장 최근 연도 (valid_from이 그날 이전인 것 중). */
  public Optional<Integer> latestYear(LocalDate day) {
    List<Integer> years =
        jdbc.queryForList(
            "select max(year) from eligibility.criteria_sets where valid_from <= ?",
            Integer.class,
            java.sql.Date.valueOf(day));
    return years.isEmpty() ? Optional.empty() : Optional.ofNullable(years.get(0));
  }
}
