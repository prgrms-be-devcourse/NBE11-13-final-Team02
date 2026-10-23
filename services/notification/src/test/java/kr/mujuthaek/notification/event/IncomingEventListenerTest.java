package kr.mujuthaek.notification.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import kr.mujuthaek.notification.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncomingEventListenerTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired RabbitTemplate rabbit;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("delete from processed_events").update();
  }

  @Test
  void recordsEventOnce() throws Exception {
    UUID eventId = UUID.randomUUID();
    String body = event(eventId, "notification.requested");

    publish("notification.requested", body);
    publish("notification.requested", body); // 같은 이벤트가 두 번 와도

    await(() -> processed(eventId) == 1);
    Thread.sleep(500); // 두 번째 메시지도 처리될 시간
    assertThat(processed(eventId)).isEqualTo(1);
  }

  @Test
  void brokenEnvelopeGoesToDeadLetterQueue() throws Exception {
    publish("notification.requested", "{\"eventType\":\"notification.requested\"}"); // eventId 없음

    Message dead = null;
    Instant until = Instant.now().plus(WAIT);
    while (dead == null && Instant.now().isBefore(until)) {
      dead = rabbit.receive(EventQueueConfig.DLQ, 500);
    }
    assertThat(dead).isNotNull();
    assertThat(jdbc.sql("select count(*) from processed_events").query(Long.class).single())
        .isZero();
  }

  private void publish(String routingKey, String body) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbit.send(EventQueueConfig.EXCHANGE, routingKey, new Message(body.getBytes(), props));
  }

  private long processed(UUID eventId) {
    return jdbc.sql("select count(*) from processed_events where event_id = :id")
        .param("id", eventId)
        .query(Long.class)
        .single();
  }

  private static String event(UUID eventId, String eventType) {
    return """
        {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-10-22T00:00:00Z",
         "producer":"api","payload":{}}
        """
        .formatted(eventId, eventType);
  }

  private static void await(Supplier<Boolean> condition) throws InterruptedException {
    Instant until = Instant.now().plus(WAIT);
    while (!condition.get()) {
      assertThat(Instant.now()).as("기다린 조건이 시간 안에 맞지 않았어요").isBefore(until);
      Thread.sleep(100);
    }
  }
}
