package kr.mujuthaek.api.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.notice.NoticeApiFixture;
import kr.mujuthaek.api.notice.dto.response.NoticeMapResponse;
import org.junit.jupiter.api.Test;

/** 지도 범위 안 공고가 핀 한도를 넘으면 지역별 묶음. 한도를 1로 줄여 봐요. 시험 좌표는 동해 바다 위예요. */
class NoticeMapClusterTest {

  private final NoticeApiFixture f = new NoticeApiFixture();

  private void site(UUID notice, double lng, double lat) {
    f.jdbc.update(
        "insert into area.notice_locations (notice_id, notice_location_id, notice_version, kind,"
            + " address_text, geom, status, method) values (?, 'building-a', 1, 'BUILDING', '주소',"
            + " st_makepoint(?, ?)::geography, 'OK', 'FILE')",
        notice,
        lng,
        lat);
  }

  @Test
  void overTheLimitTheNoticesAreGroupedByRegion() {
    site(f.notice(p -> {}), 132.50, 38.50);
    site(f.notice(p -> {}), 132.52, 38.54);
    List<String> mine = List.of(f.provider);

    NoticeMapResponse sigungu =
        f.queries.map(38.4, 132.4, 38.6, 132.6, 12, null, null, mine, null, 1);
    assertThat(sigungu.pins()).isEmpty();
    assertThat(sigungu.clusters())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.count()).isEqualTo(2);
              assertThat(c.regionCode()).hasSize(5); // 시군구
              assertThat(c.lat()).isCloseTo(38.52, within(1e-9)); // 평균
              // 묶음 이름은 한 번에 읽어도 하나씩 읽던 것과 같아요
              assertThat(c.regionName()).isEqualTo(regionName(c.regionCode()));
            });

    NoticeMapResponse sido = f.queries.map(38.4, 132.4, 38.6, 132.6, 7, null, null, mine, null, 1);
    assertThat(sido.clusters())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.regionCode()).hasSize(2);
              assertThat(c.regionName()).isEqualTo(regionName(c.regionCode()));
            });

    NoticeMapResponse pins =
        f.queries.map(
            38.4, 132.4, 38.6, 132.6, 12, null, null, mine, null, NoticeQueryService.MAP_PINS);
    assertThat(pins.pins()).hasSize(2);
    assertThat(f.queries.map(38.4, 132.4, 38.6, 132.6, 12, "BUY", null, mine, null, 300).pins())
        .isEmpty(); // 예시 공고는 임대라 BUY에서 빠져요
  }

  private String regionName(String code) {
    return new kr.mujuthaek.api.common.region.RegionQueryService(f.jdbc)
        .displayName(NoticeQueryService.regionLookup(code.length() == 2 ? code + "000" : code));
  }
}
