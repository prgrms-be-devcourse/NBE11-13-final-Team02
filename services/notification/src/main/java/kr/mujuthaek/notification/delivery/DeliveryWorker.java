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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보낼 시각이 지난 발송 줄을 채널에 넘기고 결과를 남겨요. 어느 채널이든 이 흐름은 같아요.
 *
 * <ol>
 *   <li>같은 회원·같은 채널·같은 받을 곳·같은 묶음 시각의 묶음 줄은 1통으로, 나머지는 1건씩 보내요
 *   <li>묶음이 아니고 회원이 조용한 시간을 켰으면, 22:00~08:00에는 다음 08:00으로 미뤄요 (재시도가 밤으로 밀린 경우)
 *   <li>성공하면 SENT, 실패하면 1분 → 5분 → 30분 뒤 다시, 그래도 안 되면 FAILED
 * </ol>
 *
 * <p>보낼지 말지(회원 설정)는 api가 요청을 만들 때 이미 정했어요. 여기서는 다시 따지지 않아요.
 *
 * <p>잠근 줄은 트랜잭션이 끝날 때까지 다른 서버가 가져가지 않아요. 발송이 느린 채널을 고려해 한 번에 조금씩(batch-size) 가져가요.
 */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

  private final DeliveryRepository deliveries;
  private final DeliverySchedule schedule;
  private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
  private final int batchSize;

  public DeliveryWorker(
      DeliveryRepository deliveries,
      DeliverySchedule schedule,
      List<NotificationChannel> channels,
      @Value("${notification.delivery.batch-size:20}") int batchSize) {
    this.deliveries = deliveries;
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
      groups
          .computeIfAbsent(
              List.of(d.userId(), d.channel(), String.valueOf(d.address()), slot),
              k -> new ArrayList<>())
          .add(d);
    }
    groups.values().forEach(this::deliver);
    return due.size();
  }

  private void deliver(List<DueDelivery> group) {
    DueDelivery first = group.getFirst();
    NotificationChannel channel = channels.get(first.channel());
    if (channel == null || first.address() == null) {
      deliveries.markFailed(ids(group), "CHANNEL_UNAVAILABLE", false);
      return;
    }
    if (!first.digest()) {
      Optional<Instant> later = schedule.postponeForQuiet(first.quiet());
      if (later.isPresent()) {
        deliveries.reschedule(ids(group), later.get());
        return;
      }
    }
    try {
      String messageId =
          channel.send(first.address(), group.stream().map(DueDelivery::notification).toList());
      deliveries.markSent(ids(group), messageId, schedule.now());
      log.info("발송함: userId={}, channel={}, 건수={}", first.userId(), first.channel(), group.size());
    } catch (DeliveryException e) {
      fail(group, e.code(), e.permanent());
    } catch (RuntimeException e) {
      log.warn("발송 중 예상하지 못한 오류: userId={}, channel={}", first.userId(), first.channel(), e);
      fail(group, e.getClass().getSimpleName(), false);
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
      deliveries.markFailed(ids(group), code, true);
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
