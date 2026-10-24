package kr.mujuthaek.api.area;

import static kr.mujuthaek.api.area.AreaTestDatabase.one;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** area 기초 데이터 테이블이 Flyway로 만들어지고, 적재에 쓰는 SQL 함수가 동작하는지. */
class AreaMigrationTest {

  @Test
  void createsBaseDataTables() throws Exception {
    assertThat(
            one(
                "select string_agg(table_name, ',' order by table_name) from information_schema.tables"
                    + " where table_schema = 'area'"))
        .isEqualTo("address_points,facilities,industry_group_rules,load_runs,stores");
  }

  @Test
  void uuidV7FunctionMakesVersion7Ids() throws Exception {
    assertThat(one("select substring(area.uuid_v7()::text, 15, 1)")).isEqualTo("7");
  }

  @Test
  void geographyWorksWithFlywaySearchPath() throws Exception {
    // PostGIS 타입과 함수는 public 스키마에 있어요. 마이그레이션이 geography 칸을 만들었으면 찾은 거예요.
    assertThat(
            one(
                "select udt_name from information_schema.columns"
                    + " where table_schema = 'area' and table_name = 'facilities' and column_name = 'geom'"))
        .isEqualTo("geography");
  }

  @Test
  void dataDateColumnsExplainTheirMeaning() throws Exception {
    for (String table : new String[] {"area.stores", "area.facilities", "area.address_points"}) {
      assertThat(
              one(
                  "select col_description(attrelid, attnum) from pg_attribute"
                      + " where attrelid = '"
                      + table
                      + "'::regclass and attname = 'data_date'"))
          .contains("마지막으로 바뀐 적재", "area.load_runs.data_date");
    }
  }

  @Test
  void addressPointsAreKeyedBySourceIdAndSearchableByName() throws Exception {
    assertThat(
            one(
                "select a.attname from pg_index i join pg_attribute a"
                    + " on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
                    + " where i.indrelid = 'area.address_points'::regclass and i.indisprimary"))
        .isEqualTo("address_id");
    assertThat(
            one(
                "select string_agg(indexname, ',' order by indexname) from pg_indexes"
                    + " where schemaname = 'area' and tablename = 'address_points'"))
        .isEqualTo("address_points_name_idx,address_points_pkey,address_points_scope_idx");
  }

  @Test
  void utmKCoordinatesTransformToWgs84() throws Exception {
    // 도로명주소 원천 좌표는 UTM-K(EPSG:5179). 서울시청 근처 점이 경위도로 바뀌는지.
    assertThat(
            one(
                "select round(st_y(p)::numeric, 2) || ',' || round(st_x(p)::numeric, 2) from"
                    + " (select st_transform(st_setsrid(st_makepoint(953898, 1952036), 5179), 4326) p) t"))
        .isEqualTo("37.57,126.98");
  }

  @Test
  void industryGroupRulesAreSeeded() throws Exception {
    assertThat(
            one(
                "select industry_group from area.industry_group_rules where code_prefix = 'G20405'"))
        .isEqualTo("CONVENIENCE_STORE");
  }
}
