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
        .isEqualTo(
            "facilities,industry_group_rules,load_runs,location_metrics,location_scores,"
                + "notice_locations,score_rules,score_weights,stores");
  }

  @Test
  void createsPartialSpatialIndexesForMetrics() throws Exception {
    assertThat(
            one(
                "select count(*) from pg_indexes where schemaname = 'area'"
                    + " and indexname in ('facilities_subway_gix', 'facilities_bus_gix',"
                    + " 'facilities_school_gix', 'facilities_park_gix', 'facilities_hosp2_gix',"
                    + " 'facilities_clinic_gix', 'stores_conv_gix', 'stores_mart_gix')"))
        .isEqualTo("8");
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
  void industryGroupRulesAreSeeded() throws Exception {
    assertThat(
            one(
                "select industry_group from area.industry_group_rules where code_prefix = 'G20405'"))
        .isEqualTo("CONVENIENCE_STORE");
  }
}
