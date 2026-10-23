package kr.mujuthaek.api.area.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.area.domain.AreaLoadReport;
import kr.mujuthaek.api.area.domain.AreaSource;
import kr.mujuthaek.api.area.service.AreaSourceLoadService;
import kr.mujuthaek.api.common.messaging.EventInbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class AreaSourceReadyListenerTest {

  private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());
  private final AreaSourceLoadService loads = mock(AreaSourceLoadService.class);
  private final AreaSourceReadyListener listener =
      new AreaSourceReadyListener(new EventInbox(JsonMapper.builder().build(), jdbc), loads);

  @Test
  void loadsTheFileNamedInTheEvent() {
    when(loads.load(any())).thenReturn(AreaLoadReport.skipped(UUID.randomUUID()));

    listener.on(message(UUID.randomUUID(), "STORES", "강원"));

    var command = ArgumentCaptor.forClass(AreaSourceLoadService.Command.class);
    verify(loads).load(command.capture());
    assertThat(command.getValue())
        .isEqualTo(
            new AreaSourceLoadService.Command(
                AreaSource.STORES,
                "강원",
                "area/STORES/2026-06-30/stores_gangwon.csv",
                "ab".repeat(32),
                LocalDate.of(2026, 6, 30),
                false));
  }

  @Test
  void fileProblemsAreRecordedAndNotRetried() {
    UUID id = UUID.randomUUID();
    when(loads.load(any())).thenReturn(AreaLoadReport.failed(UUID.randomUUID(), "칸이 없어요"));

    listener.on(message(id, "STORES", "강원"));
    listener.on(message(id, "STORES", "강원"));

    verify(loads, times(1)).load(any());
    assertThat(processed(id)).isTrue();
  }

  @Test
  void connectionErrorsAreNotRecorded_soTheMessageIsRetried() {
    UUID id = UUID.randomUUID();
    when(loads.load(any())).thenThrow(new IllegalStateException("S3 연결 끊김"));

    assertThatThrownBy(() -> listener.on(message(id, "STORES", "강원")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(processed(id)).isFalse();
  }

  @Test
  void unknownSourceGoesStraightToTheDeadLetterQueue() {
    assertThatThrownBy(() -> listener.on(message(UUID.randomUUID(), "SUPERMARKET", "ALL")))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    verify(loads, never()).load(any());
  }

  private boolean processed(UUID id) {
    return jdbc.queryForObject(
            "select count(*) from common.processed_events where event_id = ?", Integer.class, id)
        == 1;
  }

  private static Message message(UUID id, String source, String scope) {
    String body =
        """
        {"eventId":"%s","eventType":"area.source.ready","schemaVersion":1,
         "occurredAt":"2026-10-22T03:00:00Z","producer":"collector",
         "payload":{"source":"%s","scope":"%s","s3Key":"area/STORES/2026-06-30/stores_gangwon.csv",
                    "sha256":"%s","dataDate":"2026-06-30","fileName":"원본.csv","rows":3}}"""
            .formatted(id, source, scope, "ab".repeat(32));
    return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
