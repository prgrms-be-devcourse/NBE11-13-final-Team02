package kr.mujuthaek.api.area.event;

import java.time.LocalDate;
import kr.mujuthaek.api.area.domain.AreaLoadReport;
import kr.mujuthaek.api.area.domain.AreaSource;
import kr.mujuthaek.api.area.service.AreaSourceLoadService;
import kr.mujuthaek.api.common.messaging.EventInbox;
import kr.mujuthaek.api.common.messaging.RabbitTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * collector가 원천 파일을 S3 area/에 올리고 보내는 area.source.ready를 받아 적재해요
 * (contracts/events/area.source.ready.v1.json).
 *
 * <p>실패 처리:
 *
 * <ul>
 *   <li>적재 결과가 FAILED(칸 없음, 잘린 파일 같은 파일 문제)면 다시 해도 같으니 처리한 것으로 기록하고 끝내요. 이유는 area.load_runs에 남아요
 *   <li>S3·DB 연결 오류처럼 예외가 나면 기록하지 않고 다시 시도하고, 정해진 횟수 뒤 실패 큐(.dlq)로 가요
 *   <li>적재는 끝났는데 처리 기록 전에 멈춰서 다시 받아도, 적재기가 (원천, 범위, 지문)으로 SKIPPED 처리해요
 * </ul>
 *
 * <p>{@code @Transactional}을 붙이지 않아요 (적재기가 트랜잭션을 직접 관리해요).
 */
@Component
class AreaSourceReadyListener {

  static final String QUEUE = "mujuthaek.area.source-ready";
  static final String EVENT_TYPE = "area.source.ready";

  private final EventInbox inbox;
  private final AreaSourceLoadService loads;

  AreaSourceReadyListener(EventInbox inbox, AreaSourceLoadService loads) {
    this.inbox = inbox;
    this.loads = loads;
  }

  @RabbitListener(queues = QUEUE)
  void on(Message message) {
    inbox.handleOnce(message, EVENT_TYPE, 1, AreaSourceReady.class, this::load);
  }

  AreaLoadReport load(AreaSourceReady event) {
    return loads.load(
        new AreaSourceLoadService.Command(
            event.source(), event.scope(), event.s3Key(), event.sha256(), event.dataDate(), false));
  }

  /** area.source.ready의 payload. fileName·rows는 기록용이라 받지 않아요. */
  record AreaSourceReady(
      AreaSource source, String scope, String s3Key, String sha256, LocalDate dataDate) {}

  @Configuration
  static class Queues {

    @Bean
    Declarables areaSourceReadyQueue() {
      return RabbitTopology.queue(QUEUE, EVENT_TYPE);
    }
  }
}
