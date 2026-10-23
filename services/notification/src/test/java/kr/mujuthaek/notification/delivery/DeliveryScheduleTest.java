package kr.mujuthaek.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import kr.mujuthaek.notification.support.TimeConfig;
import org.junit.jupiter.api.Test;

class DeliveryScheduleTest {

  @Test
  void digestGoesToNextEightOrSixPm() {
    assertThat(at("2026-10-23T07:59").firstAttempt(true, false))
        .isEqualTo(seoul("2026-10-23T08:00"));
    assertThat(at("2026-10-23T08:00").firstAttempt(true, false))
        .isEqualTo(seoul("2026-10-23T08:00"));
    assertThat(at("2026-10-23T10:00").firstAttempt(true, false))
        .isEqualTo(seoul("2026-10-23T18:00"));
    assertThat(at("2026-10-23T18:01").firstAttempt(true, true))
        .isEqualTo(seoul("2026-10-24T08:00"));
  }

  @Test
  void immediateWaitsUntilEightOnlyWhenQuietHoursOn() {
    assertThat(at("2026-10-23T23:00").firstAttempt(false, true))
        .isEqualTo(seoul("2026-10-24T08:00"));
    assertThat(at("2026-10-24T03:00").firstAttempt(false, true))
        .isEqualTo(seoul("2026-10-24T08:00"));
    assertThat(at("2026-10-23T23:00").firstAttempt(false, false))
        .isEqualTo(seoul("2026-10-23T23:00"));
    assertThat(at("2026-10-23T21:59").firstAttempt(false, true))
        .isEqualTo(seoul("2026-10-23T21:59"));
  }

  @Test
  void retriesThreeTimesThenStops() {
    DeliverySchedule schedule = at("2026-10-23T10:00");
    assertThat(schedule.retryAt(1)).contains(seoul("2026-10-23T10:01"));
    assertThat(schedule.retryAt(2)).contains(seoul("2026-10-23T10:05"));
    assertThat(schedule.retryAt(3)).contains(seoul("2026-10-23T10:30"));
    assertThat(schedule.retryAt(4)).isEmpty();
  }

  private static DeliverySchedule at(String localDateTime) {
    return new DeliverySchedule(Clock.fixed(seoul(localDateTime), TimeConfig.SEOUL));
  }

  private static Instant seoul(String localDateTime) {
    return ZonedDateTime.parse(localDateTime + ":00+09:00[Asia/Seoul]").toInstant();
  }
}
