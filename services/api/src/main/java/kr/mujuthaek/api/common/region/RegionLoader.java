package kr.mujuthaek.api.common.region;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 법정동코드 전체자료로 common.regions를 채워요. 같은 파일을 다시 넣어도 결과가 같아요(코드 기준으로 덮어씀).
 *
 * <p>폐지된 코드는 지우지 않고 valid = false로 두고, 같은 시도(짧은 이름 기준) 안에 이름이 같은 유효한 코드가 있으면 그 코드를 replaced_by로
 * 이어요. 예: 강원도 춘천시(42110, 폐지) → 강원특별자치도 춘천시(51110).
 */
@Component
public class RegionLoader {

  private static final Logger log = LoggerFactory.getLogger(RegionLoader.class);
  static final Charset CP949 = Charset.forName("MS949");

  private final JdbcTemplate jdbc;

  public RegionLoader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @return 넣거나 고친 시도·시군구 수
   */
  @Transactional
  public int load(Path file, LocalDate baseDate) throws IOException {
    List<RegionCodeFile.Row> rows;
    try (BufferedReader reader = Files.newBufferedReader(file, CP949)) {
      rows = RegionCodeFile.read(reader);
    }
    // 시도부터 넣어야 시군구의 상위 코드가 맞아요
    rows.stream().filter(r -> r.level().equals("SIDO")).forEach(r -> upsert(r, baseDate));
    rows.stream().filter(r -> r.level().equals("SIGUNGU")).forEach(r -> upsert(r, baseDate));
    linkReplacements();
    log.info("지역 코드 적재: 시도·시군구 {}개 (기준일 {})", rows.size(), baseDate);
    return rows.size();
  }

  private void upsert(RegionCodeFile.Row r, LocalDate baseDate) {
    jdbc.update(
        """
        insert into common.regions (code, level, parent_code, name, full_name, short_name, valid, base_date)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (code) do update
          set level = excluded.level, parent_code = excluded.parent_code, name = excluded.name,
              full_name = excluded.full_name, short_name = excluded.short_name, valid = excluded.valid,
              base_date = excluded.base_date, updated_at = now()
        """,
        r.code(),
        r.level(),
        r.parentCode(),
        r.name(),
        r.fullName(),
        r.shortName(),
        r.valid(),
        Date.valueOf(baseDate));
  }

  private void linkReplacements() {
    // 시도: 같은 짧은 이름의 유효한 시도 (강원 42 → 51, 전북 45 → 52)
    jdbc.update(
        """
        update common.regions old set replaced_by = cur.code
        from common.regions cur
        where old.level = 'SIDO' and cur.level = 'SIDO' and not old.valid and cur.valid
          and old.short_name = cur.short_name and old.code <> cur.code
        """);
    // 시군구: 상위 시도의 짧은 이름과 시군구 이름이 같은 유효한 시군구
    jdbc.update(
        """
        update common.regions old set replaced_by = cur.code
        from common.regions cur, common.regions old_sido, common.regions cur_sido
        where old.level = 'SIGUNGU' and cur.level = 'SIGUNGU' and not old.valid and cur.valid
          and old.parent_code = old_sido.code and cur.parent_code = cur_sido.code
          and old_sido.short_name = cur_sido.short_name and old.name = cur.name and old.code <> cur.code
        """);
  }
}
