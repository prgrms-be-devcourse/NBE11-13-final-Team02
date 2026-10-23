package kr.mujuthaek.api.area.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JusoCandidatesTest {

  private static final JusoResult BUSAN = result("2617010200", "초량상로", "1", "2");
  private static final JusoResult BUSAN_OTHER_DONG = result("2617010400", "초량상로", "5", "0");
  private static final JusoResult SEOUL = result("1168010100", "테헤란로", "152", "0");

  @Test
  void singleResultIsPicked() {
    assertThat(JusoCandidates.pick(List.of(SEOUL), "2617010200", "26170")).contains(SEOUL);
  }

  @Test
  void sameEntranceListedTwiceIsPicked() {
    JusoResult sameBuilding = result("2617010200", "초량상로", "1", "2");
    assertThat(JusoCandidates.pick(List.of(BUSAN, sameBuilding), null, null)).contains(BUSAN);
  }

  @Test
  void legalDongCodePicksOne() {
    assertThat(JusoCandidates.pick(List.of(BUSAN, BUSAN_OTHER_DONG, SEOUL), "2617010400", null))
        .contains(BUSAN_OTHER_DONG);
  }

  @Test
  void sigunguPicksOneWhenDongDoesNotMatch() {
    assertThat(JusoCandidates.pick(List.of(BUSAN, SEOUL), "2617099999", "11680")).contains(SEOUL);
  }

  @Test
  void ambiguousIsNotPicked() {
    assertThat(JusoCandidates.pick(List.of(BUSAN, BUSAN_OTHER_DONG), null, "26170")).isEmpty();
    assertThat(JusoCandidates.pick(List.of(BUSAN, SEOUL), null, null)).isEmpty();
    assertThat(JusoCandidates.pick(List.of(), "2617010200", "26170")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "부산광역시 동구 초량상로 1-2|부산광역시 동구 초량상로 1-2",
        "초량상로 1-2 <A동>|초량상로 1-2 A동",
        "100%=분양 [특별]|100 분양 특별",
        "select * from 동|* from 동",
        "LOTTE TOWER 300|LOTTE TOWER 300",
        "  테헤란로   152  |테헤란로 152",
        "Union|''"
      })
  void keywordDropsCharactersTheApiRefuses(String raw, String cleaned) {
    assertThat(JusoKeyword.clean(raw)).isEqualTo(cleaned);
  }

  private static JusoResult result(String admCd, String rn, String main, String sub) {
    return new JusoResult(admCd, rn, "0", main, sub, null, null, null, null, null, null, null);
  }
}
