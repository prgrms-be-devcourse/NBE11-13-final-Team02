package kr.mujuthaek.api.area.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AreaLoadRequestTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

  @Test
  void facilitiesDefaultToAllScope() {
    assertThat(request(AreaSource.BUS_STOP, null).scope()).isEqualTo("ALL");
    assertThat(request(AreaSource.BUS_STOP, "  ").scope()).isEqualTo("ALL");
  }

  @Test
  void storesNeedSidoScope() {
    assertThatThrownBy(() -> request(AreaSource.STORES, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
    assertThat(request(AreaSource.STORES, " 강원 ").scope()).isEqualTo("강원");
  }

  @Test
  void csvSourcesHaveHeaderAndComma_andOnlyStoresNeedScope() {
    for (AreaSource source : AreaSource.values()) {
      assertThat(source.delimiter()).isEqualTo(',');
      assertThat(source.headerless()).isFalse();
      assertThat(source.scoped()).isEqualTo(source == AreaSource.STORES);
    }
  }

  @Test
  void fileKeyAndFingerprintAreRequired() {
    assertThatThrownBy(() -> new AreaLoadRequest(AreaSource.PARK, "ALL", null, "ab", DAY, false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new AreaLoadRequest(AreaSource.PARK, "ALL", "area/PARK/x.csv", null, DAY, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void onlySucceededLoadsWithRowChangesCountAsChangedData() {
    UUID id = UUID.randomUUID();
    assertThat(AreaLoadReport.skipped(id).changedData()).isFalse();
    assertThat(AreaLoadReport.failed(id, "x").changedData()).isFalse();
    assertThat(report(0, 0, 0).changedData()).isFalse();
    assertThat(report(0, 0, 3).changedData()).isTrue();
  }

  @Test
  void onlyStoresHaveNoFacilityKind() {
    for (AreaSource source : AreaSource.values()) {
      assertThat(source.facilityKind() == null).isEqualTo(source == AreaSource.STORES);
    }
  }

  private static AreaLoadRequest request(AreaSource source, String scope) {
    return new AreaLoadRequest(
        source, scope, "area/" + source + "/2026-10-21/a.csv", "ab", DAY, false);
  }

  private static AreaLoadReport report(int inserted, int updated, int deleted) {
    return new AreaLoadReport(
        UUID.randomUUID(),
        AreaLoadReport.Status.SUCCEEDED,
        10,
        0,
        0,
        0,
        inserted,
        updated,
        10 - inserted - updated,
        deleted,
        Map.of(),
        null);
  }
}
