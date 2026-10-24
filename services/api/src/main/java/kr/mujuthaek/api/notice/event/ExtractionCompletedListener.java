package kr.mujuthaek.api.notice.event;

import kr.mujuthaek.api.common.messaging.EventInbox;
import kr.mujuthaek.api.common.messaging.RabbitTopology;
import kr.mujuthaek.api.notice.service.NoticeIngestService;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * extraction이 보내는 extraction.completed를 받아 공고로 저장해요
 * (contracts/events/extraction.completed.v1.json).
 *
 * <p>큐 mujuthaek.extraction.completed(+ .dlq)는 api가 만들어요. 같은 eventId는 한 번만 처리하고, 저장 중 예외가 나면 다시 시도한
 * 뒤 실패 큐로 가요.
 */
@Component
class ExtractionCompletedListener {

  static final String QUEUE = "mujuthaek.extraction.completed";
  static final String EVENT_TYPE = "extraction.completed";

  private final EventInbox inbox;
  private final NoticeIngestService notices;

  ExtractionCompletedListener(EventInbox inbox, NoticeIngestService notices) {
    this.inbox = inbox;
    this.notices = notices;
  }

  @RabbitListener(queues = QUEUE)
  void on(Message message) {
    inbox.handleOnceWithEnvelope(
        message,
        EVENT_TYPE,
        1,
        JsonNode.class,
        (envelope, payload) -> notices.accept(envelope.eventId(), payload));
  }

  @Configuration
  static class Queues {

    @Bean
    Declarables extractionCompletedQueue() {
      return RabbitTopology.queue(QUEUE, EVENT_TYPE);
    }
  }
}
