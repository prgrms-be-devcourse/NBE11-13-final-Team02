package kr.mujuthaek.notification.notification;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.notification.delivery.DeliveryRepository;
import kr.mujuthaek.notification.delivery.DeliverySchedule;
import kr.mujuthaek.notification.delivery.NotificationChannel;
import kr.mujuthaek.notification.settings.NotificationSettings;
import kr.mujuthaek.notification.settings.NotificationSettingsRepository;
import kr.mujuthaek.notification.support.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * notification.requested를 받아 회원마다 알림 1건(notifications)과 채널별 발송 줄(deliveries)을 만들어요. 실제 발송은
 * DeliveryWorker가 해요.
 *
 * <p>받을 회원은 api가 정해서 보내요. 여기서는 회원 설정(종류·채널 켜기)만 보고, 공고 내용으로 대상을 다시 따지지 않아요.
 */
@Service
public class NotificationService {

  private final JdbcClient jdbc;
  private final NotificationTemplates templates;
  private final NotificationSettingsRepository settings;
  private final DeliveryRepository deliveries;
  private final DeliverySchedule schedule;
  private final List<NotificationChannel> channels;
  private final Clock clock;

  public NotificationService(
      JdbcClient jdbc,
      NotificationTemplates templates,
      NotificationSettingsRepository settings,
      DeliveryRepository deliveries,
      DeliverySchedule schedule,
      List<NotificationChannel> channels,
      Clock clock) {
    this.jdbc = jdbc;
    this.templates = templates;
    this.settings = settings;
    this.deliveries = deliveries;
    this.schedule = schedule;
    this.channels = channels;
    this.clock = clock;
  }

  /** 이벤트 기록(processed_events)과 같은 트랜잭션에서 불러요. 새로 만든 알림 수를 돌려줘요 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int handle(NotificationRequest request) {
    request.validate();
    NotificationTemplates.Content content = templates.render(request);
    int created = 0;
    for (UUID userId : request.userIds().stream().distinct().toList()) {
      UUID notificationId = UuidV7.next(clock);
      if (!insertNotification(notificationId, userId, request, content)) {
        continue; // 이미 보낸(만든) 알림
      }
      created++;
      Optional<NotificationSettings> found = settings.find(userId);
      if (found.isEmpty() || !found.get().wants(request.type())) {
        continue; // 알림함에만 남아요
      }
      for (NotificationChannel channel : channels) {
        if (!channel.canDeliver(userId, found.get())) {
          continue;
        }
        boolean digest = request.type().digest() && channel.supportsDigest();
        deliveries.insert(
            UuidV7.next(clock),
            notificationId,
            channel.channel(),
            digest,
            schedule.firstAttempt(digest, found.get().quietEnabled()));
      }
    }
    return created;
  }

  private boolean insertNotification(
      UUID id, UUID userId, NotificationRequest request, NotificationTemplates.Content content) {
    return jdbc.sql(
                """
                insert into notifications
                    (id, user_id, type, target_type, target_id, dedup_key, title, body, link_path)
                values (:id, :userId, :type, :targetType, :targetId, :dedupKey, :title, :body, :linkPath)
                on conflict (dedup_key) do nothing
                """)
            .param("id", id)
            .param("userId", userId)
            .param("type", request.type().name())
            .param("targetType", request.target().type().name())
            .param("targetId", request.target().id())
            .param("dedupKey", userId + ":" + request.dedupKey())
            .param("title", content.title())
            .param("body", content.body())
            .param("linkPath", request.target().linkPath())
            .update()
        == 1;
  }
}
