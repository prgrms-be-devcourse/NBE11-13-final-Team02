package kr.mujuthaek.api.area.repository;

import static kr.mujuthaek.api.area.AreaTestDatabase.exec;
import static kr.mujuthaek.api.area.AreaTestDatabase.one;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.mujuthaek.api.area.AreaTestDatabase;
import kr.mujuthaek.api.area.domain.Coordinate;
import kr.mujuthaek.api.area.domain.GeocodeResult;
import kr.mujuthaek.api.area.domain.GeocodeResult.FailReason;
import kr.mujuthaek.api.area.domain.GeocodeResult.Method;
import kr.mujuthaek.api.area.domain.GeocodeResult.Status;
import kr.mujuthaek.api.area.domain.NoticeLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PostgresNoticeLocationsTest {

  private final PostgresNoticeLocations locations =
      new PostgresNoticeLocations(AreaTestDatabase.dataSource());
  private final UUID notice = UUID.randomUUID();

  @AfterEach
  void clean() throws Exception {
    exec("delete from area.notice_locations where notice_id = '" + notice + "'");
  }

  private NoticeLocation location(int version, String address) {
    return new NoticeLocation(notice, version, "building-a", "BUILDING", address, "2611010100");
  }

  private static GeocodeResult ok(Method method) {
    return new GeocodeResult(
        Status.OK, method, new Coordinate(35.1, 129.04), "2611010100", "26110", "x", null, true);
  }

  @Test
  void savingAgainKeepsTheAreaLocationIdAndReplacesTheRest() throws Exception {
    UUID first = locations.save(location(1, "초량상로 1-2"), "키1", ok(Method.FILE));
    UUID again =
        locations.save(
            location(2, "어딘가 9"), "키2", GeocodeResult.failed(FailReason.NOT_FOUND, null));

    assertThat(again).isEqualTo(first);
    assertThat(
            one(
                "select notice_version||'|'||address_text||'|'||address_key||'|'||status||'|'"
                    + "||coalesce(method,'-')||'|'||fail_reason||'|'||(geom is null)"
                    + " from area.notice_locations where location_id = '"
                    + first
                    + "'"))
        .isEqualTo("2|어딘가 9|키2|FAILED|-|NOT_FOUND|true");
  }

  @Test
  void storesTheCoordinateAndFlags() throws Exception {
    UUID id = locations.save(location(1, "초량상로 1-2"), "키1", ok(Method.SEARCH_API));

    assertThat(
            one(
                "select round(st_y(geom::geometry)::numeric, 2)||','||round(st_x(geom::geometry)::numeric, 2)"
                    + "||'|'||method||'|'||bjd_code_given||'|'||bjd_code_found||'|'||region_mismatch"
                    + " from area.notice_locations where location_id = '"
                    + id
                    + "'"))
        .isEqualTo("35.10,129.04|SEARCH_API|2611010100|2611010100|true");
  }

  @Test
  void onlyAnExactCoordinateIsReused() {
    String key = "재사용 " + notice;
    assertThat(locations.reusable(key)).isEmpty();

    locations.save(
        location(1, "범위만"),
        key,
        GeocodeResult.approx(new Coordinate(35.0, 129.0), "2611010100", "26110"));
    assertThat(locations.reusable(key)).isEmpty(); // 대략 위치는 다시 찾아요

    locations.save(location(1, "초량상로 1-2"), key, ok(Method.COORD_API));
    GeocodeResult reused = locations.reusable(key).orElseThrow();
    assertThat(reused.method()).isEqualTo(Method.COORD_API);
    assertThat(reused.coordinate().lat()).isEqualTo(35.1);
    assertThat(reused.bjdCodeFound()).isEqualTo("2611010100");
    assertThat(reused.regionMismatch()).isTrue();
    assertThat(locations.reusable(" ")).isEmpty();
  }
}
