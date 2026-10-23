package kr.mujuthaek.notification.event;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트를 받아 한 번만 처리해요. 같은 eventId가 다시 오면(재전송·중복 발행) 건너뛰어요.
 *
 * <p>처리한 이벤트 기록(processed_events)과 처리 결과는 한 트랜잭션이에요. 처리 중 오류가 나면 기록도 남지 않아요. 봉투가 깨진 메시지는 다시 시도해도
 * 같아서 실패 큐(.dlq)로 보내요.
 */
@Component
public class IncomingEventListener {

  private static final Logger log = LoggerFactory.getLogger(IncomingEventListener.class);

  private final JsonMapper json;
  private final JdbcClient jdbc;

  public IncomingEventListener(JsonMapper json, JdbcClient jdbc) {
    this.json = json;
    this.jdbc = jdbc;
  }

  @RabbitListener(queues = EventQueueConfig.QUEUE)
  @Transactional
  public void onMessage(Message message) {
    Envelope event = parse(message.getBody());
    boolean first =
        jdbc.sql(
                    "insert into processed_events (event_id, event_type) values (:id, :type) on conflict do nothing")
                .param("id", event.eventId())
                .param("type", event.eventType())
                .update()
            == 1;
    if (!first) {
      log.debug("이미 처리한 이벤트: eventId={}, eventType={}", event.eventId(), event.eventType());
      return;
    }
    // 다음 단계: notification.requested면 발송 줄을 만들고, email.verification.requested면 인증 메일을 보내고,
    // user.withdrawn이면 그 회원의 발송 기록을 지워요
    log.info("이벤트 받음: eventId={}, eventType={}", event.eventId(), event.eventType());
  }

  private Envelope parse(byte[] body) {
    try {
      JsonNode root = json.readTree(body);
      UUID eventId = UUID.fromString(root.path("eventId").asString());
      String eventType = root.path("eventType").asString();
      if (eventType.isBlank()) {
        throw new IllegalArgumentException("eventType이 없어요");
      }
      return new Envelope(eventId, eventType);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("이벤트 봉투를 읽지 못했어요: " + e.getMessage(), e);
    }
  }

  private record Envelope(UUID eventId, String eventType) {}
}
