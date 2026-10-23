package kr.mujuthaek.notification.delivery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.notification.delivery.DeliveryRepository.DueDelivery;
import kr.mujuthaek.notification.settings.NotificationSettings;
import kr.mujuthaek.notification.settings.NotificationSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보낼 시각이 지난 발송 줄을 채널에 넘기고 결과를 남겨요. 어느 채널이든 이 흐름은 같아요.
 *
 * <ol>
 *   <li>같은 회원·같은 채널·같은 묶음 시각의 묶음 줄은 1통으로, 나머지는 1건씩 보내요
 *   <li>보내기 직전에 설정을 다시 봐요. 채널이나 종류를 껐으면 SKIPPED, 조용한 시간이면 다음 08:00으로 미뤄요
 *   <li>성공하면 SENT, 실패하면 1분 → 5분 → 30분 뒤 다시, 그래도 안 되면 FAILED
 * </ol>
 *
 * <p>잠근 줄은 트랜잭션이 끝날 때까지 다른 서버가 가져가지 않아요. 발송이 느린 채널을 고려해 한 번에 조금씩(batch-size) 가져가요.
 */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

  private final DeliveryRepository deliveries;
  private final NotificationSettingsRepository settings;
  private final DeliverySchedule schedule;
  private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
  private final int batchSize;

  public DeliveryWorker(
      DeliveryRepository deliveries,
      NotificationSettingsRepository settings,
      DeliverySchedule schedule,
      List<NotificationChannel> channels,
      @Value("${notification.delivery.batch-size:20}") int batchSize) {
    this.deliveries = deliveries;
    this.settings = settings;
    this.schedule = schedule;
    channels.forEach(channel -> this.channels.put(channel.channel(), channel));
    this.batchSize = batchSize;
  }

  /** 한 번 돌고, 가져간 발송 줄 수를 돌려줘요 */
  @Transactional
  public int runOnce() {
    List<DueDelivery> due = deliveries.lockDue(schedule.now(), batchSize);
    Map<List<Object>, List<DueDelivery>> groups = new LinkedHashMap<>();
    for (DueDelivery d : due) {
      Object slot = d.digest() ? d.scheduledAt() : d.id(); // 묶음은 같은 시각끼리, 나머지는 1건씩
      groups.computeIfAbsent(List.of(d.userId(), d.channel(), slot), k -> new ArrayList<>()).add(d);
    }
    groups.values().forEach(this::deliver);
    return due.size();
  }

  private void deliver(List<DueDelivery> group) {
    DueDelivery first = group.getFirst();
    UUID userId = first.userId();
    NotificationChannel channel = channels.get(first.channel());
    if (channel == null) {
      deliveries.markFinished(ids(group), "SKIPPED", "CHANNEL_UNAVAILABLE", false);
      return;
    }
    Optional<NotificationSettings> found = settings.find(userId);
    if (found.isEmpty() || !channel.canDeliver(userId, found.get())) {
      deliveries.markFinished(ids(group), "SKIPPED", "CHANNEL_OFF", false);
      return;
    }
    NotificationSettings current = found.get();
    List<DueDelivery> wanted = new ArrayList<>();
    List<DueDelivery> unwanted = new ArrayList<>();
    group.forEach(d -> (current.wants(d.notification().type()) ? wanted : unwanted).add(d));
    if (!unwanted.isEmpty()) {
      deliveries.markFinished(ids(unwanted), "SKIPPED", "TYPE_OFF", false);
    }
    if (wanted.isEmpty()) {
      return;
    }
    if (!first.digest()) {
      Optional<Instant> later = schedule.postponeForQuiet(current.quietEnabled());
      if (later.isPresent()) {
        deliveries.reschedule(ids(wanted), later.get());
        return;
      }
    }
    try {
      String messageId =
          channel.send(userId, wanted.stream().map(DueDelivery::notification).toList());
      deliveries.markSent(ids(wanted), messageId, schedule.now());
      log.info("발송함: userId={}, channel={}, 건수={}", userId, first.channel(), wanted.size());
    } catch (DeliveryException e) {
      fail(wanted, e.code(), e.permanent());
    } catch (RuntimeException e) {
      log.warn("발송 중 예상하지 못한 오류: userId={}, channel={}", userId, first.channel(), e);
      fail(wanted, e.getClass().getSimpleName(), false);
    }
  }

  private void fail(List<DueDelivery> group, String code, boolean permanent) {
    int attempts = group.stream().mapToInt(DueDelivery::attempts).max().orElse(0) + 1;
    Optional<Instant> retryAt = permanent ? Optional.empty() : schedule.retryAt(attempts);
    DueDelivery first = group.getFirst();
    if (retryAt.isPresent()) {
      deliveries.markRetry(ids(group), code, retryAt.get());
      log.warn(
          "발송 실패, 다시 보내요: userId={}, channel={}, error={}, attempts={}",
          first.userId(),
          first.channel(),
          code,
          attempts);
    } else {
      deliveries.markFinished(ids(group), "FAILED", code, true);
      log.warn(
          "발송 실패: userId={}, channel={}, error={}, attempts={}",
          first.userId(),
          first.channel(),
          code,
          attempts);
    }
  }

  private static List<UUID> ids(List<DueDelivery> group) {
    return group.stream().map(DueDelivery::id).toList();
  }
}
