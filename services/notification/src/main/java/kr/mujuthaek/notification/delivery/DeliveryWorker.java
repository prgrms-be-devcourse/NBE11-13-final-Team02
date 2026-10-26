package kr.mujuthaek.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
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
 *   <li>같은 회원·같은 채널·같은 받을 곳(웹 푸시는 받을 곳 상관없이)·같은 묶음 시각의 묶음 줄은 1통으로, 나머지는 1건씩 보내요. 받을 곳은 가장 최근 줄의
 *       것이에요. 묶음은 서버 한 대만 맡아서(advisory lock) 서버가 여러 대여도 두 통으로 나뉘지 않아요
 *   <li>회원이 조용한 시간을 켰으면, 22:00~08:00에는 다음 08:00으로 미뤄요 (재시도가 밤으로 밀린 경우)
 *   <li>성공하면 SENT, 실패하면 1분 → 5분 → 30분 뒤 다시(발송 서비스가 더 기다리라고 하면 그만큼), 그래도 안 되면 FAILED. 일부 기기만 못 받았으면
 *       그 기기에만 다시 보내요
 * </ol>
 *
 * <p>보낼지 말지(회원 설정)는 api가 요청을 만들 때 이미 정했어요. 여기서는 다시 따지지 않아요.
 *
 * <p>잠근 줄은 트랜잭션이 끝날 때까지 다른 서버가 가져가지 않아요. 발송이 느린 채널을 고려해 한 번에 조금씩(묶음 batch-size개, 낱개 batch-size개)
 * 가져가요.
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
    Instant now = schedule.now();
    int taken = 0;
    for (DeliveryRepository.DigestKey key : deliveries.dueDigestKeys(now, batchSize)) {
      if (!deliveries.tryLockDigest(key)) {
        continue; // 다른 서버가 이 묶음을 보내는 중
      }
      List<DueDelivery> group = deliveries.lockDigestGroup(key);
      if (!group.isEmpty()) {
        deliver(group); // 같은 회원·채널·주소·시각의 묶음 줄 전부를 1통으로
        taken += group.size();
      }
    }
    List<DueDelivery> singles = deliveries.lockDueSingles(now, batchSize);
    singles.forEach(d -> deliver(List.of(d)));
    return taken + singles.size();
  }

  private void deliver(List<DueDelivery> group) {
    DueDelivery first = group.getFirst();
    DueDelivery latest = group.getLast(); // 묶음이면 가장 최근 요청의 받을 곳·설정으로
    NotificationChannel channel = channels.get(first.channel());
    if (channel == null || latest.address() == null) {
      deliveries.markFailed(ids(group), "CHANNEL_UNAVAILABLE", false);
      return;
    }
    Optional<Instant> later = schedule.postponeForQuiet(latest.quiet());
    if (later.isPresent()) {
      deliveries.reschedule(ids(group), later.get()); // 묶음은 모두 같은 시각으로 미뤄서 계속 한 묶음
      return;
    }
    try {
      String messageId =
          channel.send(
              first.userId(),
              latest.address(),
              group.stream().map(DueDelivery::notification).toList());
      deliveries.markSent(ids(group), messageId, schedule.now());
      log.info("발송함: userId={}, channel={}, 건수={}", first.userId(), first.channel(), group.size());
    } catch (DeliveryException e) {
      fail(group, e);
    } catch (RuntimeException e) {
      log.warn("발송 중 예상하지 못한 오류: userId={}, channel={}", first.userId(), first.channel(), e);
      fail(group, new DeliveryException(e.getClass().getSimpleName(), false));
    }
  }

  private void fail(List<DueDelivery> group, DeliveryException e) {
    String code = e.code();
    int attempts = group.stream().mapToInt(DueDelivery::attempts).max().orElse(0) + 1;
    Optional<Instant> retryAt =
        e.permanent()
            ? Optional.empty()
            : schedule.retryAt(attempts).map(at -> later(at, e.retryAfter().orElse(null)));
    DueDelivery first = group.getFirst();
    if (retryAt.isPresent()) {
      deliveries.markRetry(ids(group), code, retryAt.get(), e.remainingAddress().orElse(null));
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

  /** 정한 재시도 시각과 발송 서비스가 기다리라고 한 시간 중 늦은 쪽 */
  private Instant later(Instant retryAt, Duration retryAfter) {
    if (retryAfter == null) {
      return retryAt;
    }
    Instant asked = schedule.now().plus(retryAfter);
    return asked.isAfter(retryAt) ? asked : retryAt;
  }

  private static List<UUID> ids(List<DueDelivery> group) {
    return group.stream().map(DueDelivery::id).toList();
  }
}
