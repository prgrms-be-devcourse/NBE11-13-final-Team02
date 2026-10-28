package kr.mujuthaek.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>바깥 서비스(푸시 서비스·SES)를 기다리는 동안 DB 잠금을 쥐지 않아요 (contracts/schemas/notification-tables.draft.md 발송
 * 워커):
 *
 * <ol>
 *   <li>맡기 (짧은 트랜잭션): 보낼 줄에 "{@link #LEASE} 동안 내가 보낸다"(locked_until)를 적어요. 다른 서버는 그 줄을 건너뛰어요
 *   <li>보내기 (트랜잭션 밖)
 *   <li>결과 (줄마다 짧은 트랜잭션): SENT·재시도·FAILED를 적고 맡기를 풀어요
 * </ol>
 *
 * <p>보내고 결과를 적기 전에 서버가 죽으면, 맡은 시간이 지난 뒤 다른 서버가 다시 보내요 (최소 한 번: 드물게 한 번 더 갈 수 있어요). 한 번에 조금씩(묶음
 * batch-size개, 낱개 batch-size개) 맡아요.
 */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

  /** 맡은 줄을 보내고 결과를 적기까지 넉넉한 시간. 이 시간이 지나도 결과가 없으면(서버가 죽음) 다른 서버가 다시 맡아요 */
  static final Duration LEASE = Duration.ofMinutes(10);

  private final DeliveryRepository deliveries;
  private final DeliverySchedule schedule;
  private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
  private final TransactionTemplate tx;
  private final int batchSize;

  public DeliveryWorker(
      DeliveryRepository deliveries,
      DeliverySchedule schedule,
      List<NotificationChannel> channels,
      TransactionTemplate tx,
      @Value("${notification.delivery.batch-size:20}") int batchSize) {
    this.deliveries = deliveries;
    this.schedule = schedule;
    channels.forEach(channel -> this.channels.put(channel.channel(), channel));
    this.tx = tx;
    this.batchSize = batchSize;
  }

  /** 한 번 돌고, 맡은 발송 줄 수를 돌려줘요 */
  public int runOnce() {
    List<List<DueDelivery>> groups = tx.execute(s -> claim());
    for (List<DueDelivery> group : groups) {
      deliver(group);
    }
    return groups.stream().mapToInt(List::size).sum();
  }

  /** 보낼 줄을 맡아요. 조용한 시간이면 맡지 않고 08:00으로 미뤄요 */
  private List<List<DueDelivery>> claim() {
    Instant now = schedule.now();
    Instant leaseUntil = now.plus(LEASE);
    List<List<DueDelivery>> groups = new ArrayList<>();
    for (DeliveryRepository.DigestKey key : deliveries.dueDigestKeys(now, batchSize)) {
      if (!deliveries.tryLockDigest(key)) {
        continue; // 다른 서버가 이 묶음을 맡는 중
      }
      List<DueDelivery> group = deliveries.claimDigestGroup(key, now, leaseUntil);
      if (!group.isEmpty()) {
        groups.add(group); // 같은 회원·채널·주소·시각의 묶음 줄 전부를 1통으로
      }
    }
    for (DueDelivery single : deliveries.claimDueSingles(now, batchSize, leaseUntil)) {
      groups.add(List.of(single));
    }
    List<List<DueDelivery>> ready = new ArrayList<>();
    for (List<DueDelivery> group : groups) {
      Optional<Instant> later = schedule.postponeForQuiet(group.getLast().quiet());
      if (later.isPresent()) {
        deliveries.reschedule(ids(group), later.get()); // 묶음은 모두 같은 시각으로 미뤄서 계속 한 묶음
      } else {
        ready.add(group);
      }
    }
    return ready;
  }

  /** 트랜잭션 밖에서 보내고, 결과는 짧은 트랜잭션으로 적어요 */
  private void deliver(List<DueDelivery> group) {
    DueDelivery first = group.getFirst();
    DueDelivery latest = group.getLast(); // 묶음이면 가장 최근 요청의 받을 곳·설정으로
    NotificationChannel channel = channels.get(first.channel());
    if (channel == null || latest.address() == null) {
      tx.executeWithoutResult(s -> deliveries.markFailed(ids(group), "CHANNEL_UNAVAILABLE", false));
      return;
    }
    try {
      String messageId =
          channel.send(
              first.userId(),
              latest.address(),
              group.stream().map(DueDelivery::notification).toList());
      tx.executeWithoutResult(s -> deliveries.markSent(ids(group), messageId, schedule.now()));
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
      tx.executeWithoutResult(
          s ->
              deliveries.markRetry(
                  ids(group), code, retryAt.get(), e.remainingAddress().orElse(null)));
      log.warn(
          "발송 실패, 다시 보내요: userId={}, channel={}, error={}, attempts={}",
          first.userId(),
          first.channel(),
          code,
          attempts);
    } else {
      tx.executeWithoutResult(s -> deliveries.markFailed(ids(group), code, true));
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
