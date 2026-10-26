package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.response.SpaceSearchResponse;
import kr.mujuthaek.api.eligibility.rule.Eligibility;
import org.junit.jupiter.api.Test;

/** 사업공간 맞춤 목록의 지역 거르기·모집 정책 접수 상태·정렬. 판정은 {@link MatchQueryServiceTest}에서 봐요. */
class SpaceSearchServiceTest {

  @Test
  void regionsMatchBySidoOrSigungu() {
    // 고른 지역이 없거나 전국(빈 목록)·지역 모름(null) 공간은 늘 걸려요
    assertThat(SpaceSearchService.inRegions(List.of("1120011500"), null)).isTrue();
    assertThat(SpaceSearchService.inRegions(List.of(), List.of("11200"))).isTrue();
    assertThat(SpaceSearchService.inRegions(null, List.of("11200"))).isTrue();
    // 시군구 5자리·시도 2자리로 골라요
    assertThat(SpaceSearchService.inRegions(List.of("1120011500"), List.of("11200"))).isTrue();
    assertThat(SpaceSearchService.inRegions(List.of("1120011500"), List.of("11"))).isTrue();
    assertThat(SpaceSearchService.inRegions(List.of("1120011500"), List.of("11440"))).isFalse();
    // 공간이 시도 전체면 그 시도의 시군구를 골라도 걸려요
    assertThat(SpaceSearchService.inRegions(List.of("1100000000"), List.of("11440"))).isTrue();
    assertThat(SpaceSearchService.inRegions(List.of("1100000000"), List.of("26110"))).isFalse();
  }

  @Test
  void policyStatusComesFromItsPeriod() {
    LocalDate today = LocalDate.parse("2026-10-26");
    assertThat(SpaceSearchService.status(null, null, today)).isEqualTo("ALWAYS");
    assertThat(
            SpaceSearchService.status(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-10"), today))
        .isEqualTo("UPCOMING");
    assertThat(
            SpaceSearchService.status(
                LocalDate.parse("2026-10-20"), LocalDate.parse("2026-11-10"), today))
        .isEqualTo("APPLYING");
  }

  @Test
  void recommendedPutsEligibleFirstThenDeadline() {
    var late = item("00000000-0000-0000-0000-000000000001", Eligibility.ELIGIBLE, "2026-11-30");
    var soon = item("00000000-0000-0000-0000-000000000002", Eligibility.NEEDS_INPUT, "2026-10-30");
    var always = item("00000000-0000-0000-0000-000000000003", Eligibility.NEEDS_INPUT, null);
    var no = item("00000000-0000-0000-0000-000000000004", Eligibility.INELIGIBLE, "2026-10-27");
    List<SpaceSearchResponse.Item> items = List.of(no, always, soon, late);

    assertThat(items.stream().sorted(SpaceSearchService.order("RECOMMENDED")).toList())
        .containsExactly(late, soon, always, no);
    assertThat(items.stream().sorted(SpaceSearchService.order("DEADLINE")).toList())
        .containsExactly(no, soon, late, always);
  }

  private static SpaceSearchResponse.Item item(String id, Eligibility overall, String applyEnd) {
    return new SpaceSearchResponse.Item(
        UUID.fromString(id),
        false,
        "STARTUP_SPACE",
        "공간",
        null,
        null,
        List.of(),
        "APPLYING",
        null,
        applyEnd == null ? null : LocalDate.parse(applyEnd),
        null,
        overall,
        null,
        List.of(),
        List.of(),
        null,
        List.of());
  }
}
