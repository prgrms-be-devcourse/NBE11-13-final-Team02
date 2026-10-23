package kr.mujuthaek.notification.notification;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kr.mujuthaek.notification.delivery.DeliveryRepository;
import kr.mujuthaek.notification.delivery.DeliverySchedule;
import kr.mujuthaek.notification.delivery.NotificationChannel;
import kr.mujuthaek.notification.support.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * notification.requested를 받아 회원마다·채널마다 발송 줄(deliveries)을 만들어요. 실제 발송은 DeliveryWorker가 해요.
 *
 * <p>받을 사람과 받을 주소는 api가 정해서 보내요. 여기서는 요청에 그 채널 주소가 있으면 줄을 만들고, 보낼 시각만 정해요.
 */
@Service
public class NotificationService {

  private final DeliveryRepository deliveries;
  private final DeliverySchedule schedule;
  private final List<NotificationChannel> channels;
  private final Clock clock;

  public NotificationService(
      DeliveryRepository deliveries,
      DeliverySchedule schedule,
      List<NotificationChannel> channels,
      Clock clock) {
    this.deliveries = deliveries;
    this.schedule = schedule;
    this.channels = channels;
    this.clock = clock;
  }

  /** 이벤트 기록(processed_events)과 같은 트랜잭션에서 불러요. 새로 만든 발송 줄 수를 돌려줘요 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int handle(NotificationRequest request) {
    request.validate();
    int created = 0;
    for (NotificationRequest.Recipient recipient : request.recipients()) {
      for (NotificationChannel channel : channels) {
        Optional<String> address = channel.address(recipient);
        if (address.isEmpty()) {
          continue; // 이 채널로는 받지 않아요 (api가 주소를 넣지 않음)
        }
        boolean digest = request.type().digest() && channel.supportsDigest();
        boolean inserted =
            deliveries.insert(
                new DeliveryRepository.NewDelivery(
                    UuidV7.next(clock),
                    recipient.userId(),
                    channel.channel(),
                    address.get(),
                    request.type(),
                    recipient.userId() + ":" + request.dedupKey(),
                    request.title(),
                    request.body(),
                    request.linkPath(),
                    digest,
                    recipient.quietEnabled(),
                    schedule.firstAttempt(digest, recipient.quietEnabled())));
        if (inserted) {
          created++;
        }
      }
    }
    return created;
  }
}
