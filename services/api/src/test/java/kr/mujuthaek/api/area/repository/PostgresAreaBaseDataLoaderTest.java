package kr.mujuthaek.api.area.repository;

import static kr.mujuthaek.api.area.AreaTestDatabase.exec;
import static kr.mujuthaek.api.area.AreaTestDatabase.one;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import kr.mujuthaek.api.area.AreaTestDatabase;
import kr.mujuthaek.api.area.domain.AreaLoadReport;
import kr.mujuthaek.api.area.domain.AreaLoadRequest;
import kr.mujuthaek.api.area.domain.AreaSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 PostGIS에 마이그레이션을 적용하고, 원천 파일과 같은 인코딩(CP949·UTF-8)의 자료로 적재해요. */
class PostgresAreaBaseDataLoaderTest {

  private final PostgresAreaBaseDataLoader loader =
      new PostgresAreaBaseDataLoader(AreaTestDatabase.dataSource());

  @BeforeEach
  void clean() throws Exception {
    exec("truncate area.facilities, area.stores, area.load_runs");
  }

  @Test
  void busStops_rejectsBadRows_fixesSwappedCoordinates_skipsBlankLines() throws Exception {
    AreaLoadReport r = load(AreaSource.BUS_STOP, "ALL", "bus_stop.csv", false);

    assertThat(r.status()).isEqualTo(AreaLoadReport.Status.SUCCEEDED);
    assertThat(r.rowsIn()).isEqualTo(8);
    assertThat(r.rowsInserted()).isEqualTo(3);
    assertThat(r.rowsFixed()).isEqualTo(1);
    assertThat(r.rejectReasons())
        .isEqualTo(Map.of("NO_COORD", 2, "OUT_OF_RANGE", 1, "DUPLICATE_KEY", 1, "NO_KEY", 1));
    assertThat(
            one(
                "select round(st_y(geom::geometry)::numeric,4)||','||round(st_x(geom::geometry)::numeric,4) "
                    + "from area.facilities where source_key='SWAP0001'"))
        .isEqualTo("37.5665,126.9780");
    assertThat(one("select substring(id::text,15,1) from area.facilities limit 1")).isEqualTo("7");
    assertThat(one("select status from area.load_runs")).isEqualTo("SUCCEEDED");
  }

  @Test
  void subwayStations_keepLineAsSubKind() throws Exception {
    AreaLoadReport r = load(AreaSource.SUBWAY_STATION, "ALL", "subway_station.csv", false);

    assertThat(r.rowsInserted()).isEqualTo(2);
    assertThat(
            one(
                "select string_agg(name||':'||sub_kind, ',' order by source_key) from area.facilities"))
        .isEqualTo("신사:신분당선,논현:신분당선");
  }

  @Test
  void schoolsParksHospitals_followFilterRules() throws Exception {
    assertThat(load(AreaSource.SCHOOL, "ALL", "school.csv", false).rowsInserted()).isEqualTo(2);
    assertThat(
            one(
                "select string_agg(sub_kind, ',' order by sub_kind) from area.facilities where kind='SCHOOL'"))
        .isEqualTo("ELEMENTARY,HIGH");
    assertThat(load(AreaSource.PARK, "ALL", "park.csv", false).rowsInserted()).isEqualTo(2);
    assertThat(
            one(
                "select string_agg(extra->>'area_m2', ',' order by (extra->>'area_m2')::numeric) "
                    + "from area.facilities where kind='PARK'"))
        .isEqualTo("1400,189000");
    AreaLoadReport h = load(AreaSource.HOSPITAL, "ALL", "hospital.csv", false);
    assertThat(h.rowsInserted()).isEqualTo(3);
    assertThat(h.rowsSkipped()).isEqualTo(2);
    assertThat(
            one(
                "select string_agg(sub_kind, ',' order by sub_kind) from area.facilities where kind='HOSPITAL'"))
        .isEqualTo("CLINIC,CLINIC,SECONDARY");
  }

  @Test
  void missingColumn_failsAndKeepsPreviousData() throws Exception {
    load(AreaSource.HOSPITAL, "ALL", "hospital.csv", false);
    AreaLoadReport r = load(AreaSource.HOSPITAL, "ALL", "hospital_no_coord_column.csv", false);

    assertThat(r.status()).isEqualTo(AreaLoadReport.Status.FAILED);
    assertThat(r.error()).contains("좌표(X)");
    assertThat(one("select count(*) from area.facilities where kind='HOSPITAL'")).isEqualTo("3");
    assertThat(one("select count(*) from area.load_runs where status='FAILED'")).isEqualTo("1");
  }

  @Test
  void loadingTheSameSourceAgain_keepsUnchangedRows() throws Exception {
    load(AreaSource.SCHOOL, "ALL", "school.csv", false);
    exec("delete from area.load_runs");
    AreaLoadReport again = load(AreaSource.SCHOOL, "ALL", "school.csv", false);

    assertThat(again.rowsInserted()).isZero();
    assertThat(again.rowsUpdated()).isZero();
    assertThat(again.rowsUnchanged()).isEqualTo(2);
    assertThat(again.changedData()).isFalse();
  }

  static AreaLoadReport load(
      PostgresAreaBaseDataLoader loader,
      AreaSource source,
      String scope,
      String file,
      boolean allowShrink)
      throws Exception {
    Path path = fixture(file);
    String sha =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    var req =
        new AreaLoadRequest(
            source,
            scope,
            "area/" + source + "/2026-10-21/" + file,
            sha,
            LocalDate.of(2026, 10, 21),
            allowShrink);
    try (InputStream in = Files.newInputStream(path)) {
      return loader.load(req, in);
    }
  }

  static Path fixture(String name) throws Exception {
    return Path.of(
        PostgresAreaBaseDataLoaderTest.class
            .getClassLoader()
            .getResource("area/fixtures/" + name)
            .toURI());
  }

  private AreaLoadReport load(AreaSource source, String scope, String file, boolean allowShrink)
      throws Exception {
    return load(loader, source, scope, file, allowShrink);
  }
}
