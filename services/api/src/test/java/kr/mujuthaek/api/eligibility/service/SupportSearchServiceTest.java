package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.response.SupportSearchResponse;
import kr.mujuthaek.api.eligibility.rule.Eligibility;
import org.junit.jupiter.api.Test;

/** 사업 지원 맞춤 목록의 지원 종류 나누기·같은 사업 하나만. 판정은 {@link MatchQueryServiceTest}에서 봐요. */
class SupportSearchServiceTest {

  @Test
  void subFieldComesFromTheName() {
    assertThat(SupportSearchService.subField(null, "2026년 용산구 청년기업 융자지원사업")).isEqualTo("LOAN");
    assertThat(SupportSearchService.subField(null, "청년 창업 정책자금")).isEqualTo("LOAN");
    assertThat(SupportSearchService.subField(null, "청년 창업 세액감면 안내")).isEqualTo("TAX");
    assertThat(SupportSearchService.subField(null, "2026년 예비창업패키지 사업화 자금 지원")).isEqualTo("FUND");
    assertThat(SupportSearchService.subField(null, "창업보육센터 입주기업 지원")).isEqualTo("FUND");
    assertThat(SupportSearchService.subField(null, null)).isEqualTo("FUND");
    // 출처의 지원 분야가 있으면 그걸로 (이름에 없어도)
    assertThat(SupportSearchService.subField("융자", "2026 청년 창업 지원")).isEqualTo("LOAN");
    assertThat(SupportSearchService.subField("사업화", "2026 청년 창업 지원")).isEqualTo("FUND");
  }

  @Test
  void sameProgramFromTwoSitesShowsOnce() {
    var bizinfo = item("00000000-0000-0000-0000-000000000021", "BIZINFO", "[서울] 2026 웰컴 투 팁스 모집");
    var other = item("00000000-0000-0000-0000-000000000022", "BIZINFO", "김해시 창업 지원금");
    var kstartup = item("00000000-0000-0000-0000-000000000023", "KSTARTUP", "서울 2026 웰컴 투 팁스 모집");

    assertThat(SupportSearchService.withoutDuplicates(List.of(bizinfo, other, kstartup)))
        .containsExactly(other, kstartup);
  }

  private static SupportSearchResponse.Item item(String id, String source, String title) {
    return new SupportSearchResponse.Item(
        UUID.fromString(id),
        source,
        "FUND",
        null,
        null,
        null,
        title,
        List.of(),
        "APPLYING",
        null,
        null,
        null,
        Eligibility.NEEDS_INPUT,
        null,
        List.of(),
        List.of(),
        null);
  }
}
