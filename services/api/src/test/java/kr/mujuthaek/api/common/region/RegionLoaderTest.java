package kr.mujuthaek.api.common.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import kr.mujuthaek.api.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 법정동코드 전체자료와 같은 형식(탭 구분, CP949)의 짧은 자료로 확인해요. */
class RegionLoaderTest {

  private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());
  private final RegionLoader loader = new RegionLoader(jdbc);

  @BeforeEach
  void clean() {
    jdbc.update("update common.regions set replaced_by = null");
    jdbc.update("delete from common.regions where level = 'SIGUNGU'");
    jdbc.update("delete from common.regions");
  }

  @Test
  void keepsOnlySidoAndSigungu() throws Exception {
    var rows =
        RegionCodeFile.read(
            new BufferedReader(
                new StringReader(
                    "법정동코드\t법정동명\t폐지여부\n"
                        + "1100000000\t서울특별시\t존재\n"
                        + "1144000000\t서울특별시 마포구\t존재\n"
                        + "1144010100\t서울특별시 마포구 아현동\t존재\n")));

    assertThat(rows)
        .containsExactly(
            new RegionCodeFile.Row("11", "SIDO", null, "서울특별시", "서울특별시", "서울", true),
            new RegionCodeFile.Row("11440", "SIGUNGU", "11", "마포구", "서울특별시 마포구", null, true));
  }

  @Test
  void loadsTheFileAndLinksReplacedCodes() throws Exception {
    int count = loader.load(sample(), LocalDate.of(2026, 7, 1));

    assertThat(count).isEqualTo(11);
    assertThat(names("SIDO")).containsExactly("11:서울", "36:세종", "41:경기", "42:강원", "51:강원");
    assertThat(
            jdbc.queryForObject(
                "select name || '|' || full_name from common.regions where code = '41111'",
                String.class))
        .isEqualTo("수원시 장안구|경기도 수원시 장안구");
    assertThat(
            jdbc.queryForObject(
                "select name from common.regions where code = '36110'", String.class))
        .isEqualTo("세종특별자치시");
    assertThat(replacedBy("42")).isEqualTo("51");
    assertThat(replacedBy("42110")).isEqualTo("51110");
    assertThat(replacedBy("51110")).isNull();
  }

  @Test
  void loadingAgainGivesTheSameResult() throws Exception {
    loader.load(sample(), LocalDate.of(2026, 7, 1));
    loader.load(sample(), LocalDate.of(2026, 7, 1));

    assertThat(jdbc.queryForObject("select count(*) from common.regions", Integer.class))
        .isEqualTo(11);
  }

  @Test
  void sidoShortNames() {
    assertThat(RegionCodeFile.shortName("전북특별자치도")).isEqualTo("전북");
    assertThat(RegionCodeFile.shortName("전라남도")).isEqualTo("전남");
    assertThat(RegionCodeFile.shortName("경상북도")).isEqualTo("경북");
    assertThat(RegionCodeFile.shortName("제주특별자치도")).isEqualTo("제주");
  }

  private List<String> names(String level) {
    return jdbc.queryForList(
        "select code || ':' || short_name from common.regions where level = ? order by code",
        String.class,
        level);
  }

  private String replacedBy(String code) {
    return jdbc.queryForObject(
        "select replaced_by from common.regions where code = ?", String.class, code);
  }

  private static Path sample() throws Exception {
    return Path.of(
        RegionLoaderTest.class
            .getClassLoader()
            .getResource("common/region_codes_sample.txt")
            .toURI());
  }
}
