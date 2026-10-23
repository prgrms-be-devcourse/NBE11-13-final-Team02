package kr.mujuthaek.notification.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.notification.notification.NotificationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** deliveries 읽기·쓰기. 상태는 PENDING → SENT / FAILED / SKIPPED / CANCELLED 예요. */
@Repository
public class DeliveryRepository {

  private final JdbcClient jdbc;

  public DeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 보낼 시각이 지난 발송 줄 + 알림 내용. 서버가 여러 대여도 같은 줄을 두 번 가져가지 않게 잠가요 (트랜잭션 안에서 불러요) */
  public List<DueDelivery> lockDue(Instant now, int limit) {
    return jdbc.sql(
            """
            select d.id, d.channel, d.digest, d.scheduled_at, d.attempts,
                   n.id as notification_id, n.user_id, n.type, n.title, n.body, n.link_path
              from deliveries d join notifications n on n.id = d.notification_id
             where d.status = 'PENDING' and d.scheduled_at <= :now
             order by d.scheduled_at, n.user_id
             limit :limit
               for update of d skip locked
            """)
        .param("now", at(now))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new DueDelivery(
                    rs.getObject("id", UUID.class),
                    Channel.valueOf(rs.getString("channel")),
                    rs.getBoolean("digest"),
                    rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("attempts"),
                    rs.getObject("user_id", UUID.class),
                    new OutgoingNotification(
                        rs.getObject("notification_id", UUID.class),
                        NotificationType.valueOf(rs.getString("type")),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("link_path"))))
        .list();
  }

  public void insert(UUID id, UUID notificationId, Channel channel, boolean digest, Instant at) {
    jdbc.sql(
            """
            insert into deliveries (id, notification_id, channel, digest, scheduled_at)
            values (:id, :notificationId, :channel, :digest, :at)
            """)
        .param("id", id)
        .param("notificationId", notificationId)
        .param("channel", channel.name())
        .param("digest", digest)
        .param("at", at(at))
        .update();
  }

  public void markSent(List<UUID> ids, String providerMessageId, Instant now) {
    jdbc.sql(
            """
            update deliveries
               set status = 'SENT', attempts = attempts + 1, provider_message_id = :messageId,
                   sent_at = :now, last_error = null
             where id in (:ids)
            """)
        .param("ids", ids)
        .param("messageId", providerMessageId)
        .param("now", at(now))
        .update();
  }

  public void markRetry(List<UUID> ids, String error, Instant retryAt) {
    jdbc.sql(
            """
            update deliveries set attempts = attempts + 1, last_error = :error, scheduled_at = :at
             where id in (:ids)
            """)
        .param("ids", ids)
        .param("error", error)
        .param("at", at(retryAt))
        .update();
  }

  /** 시도하고 실패했으면 FAILED(attempts + 1), 시도하지 않고 멈췄으면 SKIPPED */
  public void markFinished(List<UUID> ids, String status, String error, boolean attempted) {
    jdbc.sql(
            """
            update deliveries
               set status = :status, last_error = :error,
                   attempts = attempts + case when :attempted then 1 else 0 end
             where id in (:ids)
            """)
        .param("ids", ids)
        .param("status", status)
        .param("error", error)
        .param("attempted", attempted)
        .update();
  }

  public void reschedule(List<UUID> ids, Instant at) {
    jdbc.sql("update deliveries set scheduled_at = :at where id in (:ids)")
        .param("ids", ids)
        .param("at", at(at))
        .update();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /** 보낼 차례가 된 발송 줄 1개 */
  public record DueDelivery(
      UUID id,
      Channel channel,
      boolean digest,
      Instant scheduledAt,
      int attempts,
      UUID userId,
      OutgoingNotification notification) {}
}
