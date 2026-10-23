package kr.mujuthaek.notification.event;

import java.util.UUID;
import kr.mujuthaek.notification.notification.NotificationRequest;
import kr.mujuthaek.notification.notification.NotificationService;
import kr.mujuthaek.notification.notification.UserDataCleaner;
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
  private final NotificationService notifications;
  private final UserDataCleaner cleaner;

  public IncomingEventListener(
      JsonMapper json,
      JdbcClient jdbc,
      NotificationService notifications,
      UserDataCleaner cleaner) {
    this.json = json;
    this.jdbc = jdbc;
    this.notifications = notifications;
    this.cleaner = cleaner;
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
    switch (event.eventType()) {
      case "notification.requested" -> {
        int created = notifications.handle(payload(event, NotificationRequest.class));
        log.info("알림 요청 처리: eventId={}, 새 알림={}", event.eventId(), created);
      }
      case "user.withdrawn" -> {
        cleaner.delete(payload(event, Withdrawn.class).userId());
        log.info("탈퇴 회원 알림 데이터 지움: eventId={}", event.eventId());
      }
      default ->
          log.warn("모르는 이벤트라 건너뜀: eventId={}, eventType={}", event.eventId(), event.eventType());
    }
  }

  /** payload가 계약과 다르면 다시 받아도 같아서 실패 큐로 보내요 */
  private <T> T payload(Envelope event, Class<T> type) {
    try {
      T value = json.treeToValue(event.payload(), type);
      if (value == null) {
        throw new IllegalArgumentException("payload가 없어요");
      }
      if (value instanceof NotificationRequest request) {
        request.validate();
      }
      if (value instanceof Withdrawn withdrawn && withdrawn.userId() == null) {
        throw new IllegalArgumentException("userId가 없어요");
      }
      return value;
    } catch (JacksonException | IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException(
          "payload를 읽지 못했어요: eventId=" + event.eventId() + ", " + e.getMessage(), e);
    }
  }

  private Envelope parse(byte[] body) {
    try {
      JsonNode root = json.readTree(body);
      UUID eventId = UUID.fromString(root.path("eventId").asString());
      String eventType = root.path("eventType").asString();
      if (eventType.isBlank()) {
        throw new IllegalArgumentException("eventType이 없어요");
      }
      return new Envelope(eventId, eventType, root.path("payload"));
    } catch (JacksonException | IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("이벤트 봉투를 읽지 못했어요: " + e.getMessage(), e);
    }
  }

  private record Envelope(UUID eventId, String eventType, JsonNode payload) {}

  private record Withdrawn(UUID userId) {}
}
