package kr.mujuthaek.notification.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.notification.TestcontainersConfiguration;
import kr.mujuthaek.notification.event.IncomingEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 동시성 시나리오 4-2: 같은 알림 요청(eventId 같음)을 100번 동시에 받아도 처리 기록 1줄, 발송 줄은 한 번 받은 것과 같은지. 10회 반복.
 * Testcontainers DB라 운영·dev 데이터는 건드리지 않아요. perf/concurrency/run.sh가 CONCURRENCY=1로 돌려요.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "CONCURRENCY", matches = "1")
class IncomingEventConcurrencyTest {

  @Autowired IncomingEventListener listener;
  @Autowired JdbcClient jdbc;

  @Test
  void sameRequestReceivedAtOnceIsProcessedOnce() throws Exception {
    List<Map<String, Object>> rounds = new ArrayList<>();
    boolean allPass = true;
    for (int round = 1; round <= ConcurrencyRunner.ROUNDS; round++) {
      UUID eventId = UUID.randomUUID();
      UUID user = UUID.randomUUID();
      String body = event(eventId, user);
      Map<String, Integer> outcomes =
          ConcurrencyRunner.runAtOnce(
              i -> {
                listener.onMessage(message(body));
                return "OK";
              });
      long processed = count("select count(*) from processed_events where event_id = :id", eventId);
      long deliveries = count("select count(*) from deliveries where user_id = :id", user);
      boolean errors = outcomes.keySet().stream().anyMatch(k -> k.startsWith("예외:"));
      boolean pass = processed == 1 && deliveries == 1 && !errors;
      allPass &= pass;
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("round", round);
      r.put("pass", pass);
      r.put("outcomes", outcomes);
      r.put("db", Map.of("processed_events", processed, "deliveries", deliveries));
      rounds.add(r);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scenario", "4-2 알림 요청 리스너: 같은 eventId 동시 수신");
    result.put("criteria", "처리 기록 1줄, 받는 사람 한 명(이메일)의 발송 줄 1줄, 예외 없음");
    result.put("pass", allPass);
    result.put("rounds", rounds);
    ConcurrencyRunner.write("4-2_notification", result);
    assertThat(allPass).as("결과: %s", rounds).isTrue();
  }

  private long count(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  private static Message message(String body) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    return new Message(body.getBytes(), props);
  }

  private static String event(UUID eventId, UUID user) {
    return """
        {"eventId":"%s","eventType":"notification.requested","schemaVersion":1,"occurredAt":"2026-10-22T00:00:00Z",
         "producer":"api","payload":{"type":"DEADLINE_D1","dedupKey":"NOTICE:%s:D1:2026-11-06",
         "target":{"type":"NOTICE","id":"%s"},"title":"내일 마감: 강동구 행복주택",
         "body":"관심 공고 접수가 내일(11월 6일) 마감돼요.","linkPath":"/notices/%s",
         "recipients":[{"userId":"%s","email":"perf-%s@example.invalid","quietEnabled":false}]}}
        """
        .formatted(eventId, eventId, eventId, eventId, user, user);
  }
}
