package kr.mujuthaek.api.common.region;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 지역 코드 조회. 다른 모듈도 지역 이름(regionName)을 붙일 때 써요. */
@Service
public class RegionQueryService {

  private final JdbcTemplate jdbc;

  public RegionQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Sigungu(String code, String name) {}

  public record Sido(String code, String name, String shortName, List<Sigungu> sigungus) {}

  public record RegionTree(LocalDate baseDate, List<Sido> sidos) {}

  /**
   * @param valid 폐지된 코드면 false, replacedBy가 바뀐 코드
   */
  public record Region(
      String code,
      String level,
      String name,
      String fullName,
      boolean valid,
      String replacedBy,
      LocalDate validTo) {}

  /** 유효한 시도와 그 아래 시군구 (코드 순). */
  public RegionTree tree() {
    Map<String, Sido> sidos = new LinkedHashMap<>();
    jdbc.query(
        "select code, name, short_name from common.regions where level = 'SIDO' and valid order by code",
        rs -> {
          sidos.put(
              rs.getString(1),
              new Sido(rs.getString(1), rs.getString(2), rs.getString(3), new ArrayList<>()));
        });
    jdbc.query(
        "select code, name, parent_code from common.regions where level = 'SIGUNGU' and valid order by code",
        rs -> {
          Sido sido = sidos.get(rs.getString(3));
          if (sido != null) {
            sido.sigungus().add(new Sigungu(rs.getString(1), rs.getString(2)));
          }
        });
    LocalDate baseDate =
        jdbc.queryForObject("select max(base_date) from common.regions", LocalDate.class);
    return new RegionTree(baseDate, List.copyOf(sidos.values()));
  }

  public Optional<Region> find(String code) {
    return jdbc
        .query(
            "select code, level, name, full_name, valid, replaced_by, valid_to from common.regions where code = ?",
            (rs, i) ->
                new Region(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    rs.getString(6),
                    rs.getObject(7, LocalDate.class)),
            code)
        .stream()
        .findFirst();
  }

  /** 화면에 붙일 지역 이름 ("서울 마포구"). 모르는 코드면 null. */
  public String displayName(String code) {
    if (code == null) {
      return null;
    }
    return jdbc
        .query(
            """
            select coalesce(s.short_name || ' ' || r.name, r.short_name, r.name)
            from common.regions r left join common.regions s on s.code = r.parent_code
            where r.code = ?
            """,
            (rs, i) -> rs.getString(1),
            code)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** ETag용: 코드표가 바뀌면 달라지는 값. */
  String version() {
    return jdbc.queryForObject(
        "select count(*) || '-' || coalesce(extract(epoch from max(updated_at))::bigint, 0) from common.regions",
        String.class);
  }
}
