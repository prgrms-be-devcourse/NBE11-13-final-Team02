package kr.mujuthaek.notification.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 발송(1초마다)과 정리(매일 04:20). 테스트에서는 꺼 두고(notification.outbox.scheduling-enabled=false) 직접 불러요.
 */
@Component
@ConditionalOnProperty(
    name = "notification.outbox.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OutboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

  private final OutboxRelay relay;

  OutboxScheduler(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${notification.outbox.relay-interval-ms:1000}")
  void relay() {
    try {
      int sent;
      do {
        sent = relay.relayOnce();
      } while (sent == OutboxRelay.BATCH_SIZE);
    } catch (RuntimeException e) {
      log.warn("아웃박스 발송 실패, 다음 차례에 다시 해요: {}", e.getClass().getSimpleName());
    }
  }

  @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
  void cleanup() {
    int deleted = relay.deletePublished();
    if (deleted > 0) {
      log.info("보낸 아웃박스 줄 지움: {}", deleted);
    }
  }
}
