package kr.mujuthaek.notification.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.notification.notification.NotificationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** deliveries 읽기·쓰기. 상태는 PENDING → SENT / FAILED 예요. 끝나면 받을 곳(address)을 지워요. */
@Repository
public class DeliveryRepository {

  private final JdbcClient jdbc;

  public DeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 회원·같은 알림·같은 채널 줄이 이미 있으면(같은 요청이 다시 옴) 넣지 않고 false */
  public boolean insert(NewDelivery d) {
    return jdbc.sql(
                """
                insert into deliveries (id, user_id, channel, address, type, dedup_key, title, body,
                                        link_path, digest, quiet, scheduled_at)
                values (:id, :userId, :channel, :address, :type, :dedupKey, :title, :body,
                        :linkPath, :digest, :quiet, :at)
                on conflict (dedup_key, channel) do nothing
                """)
            .param("id", d.id())
            .param("userId", d.userId())
            .param("channel", d.channel().name())
            .param("address", d.address())
            .param("type", d.type().name())
            .param("dedupKey", d.dedupKey())
            .param("title", d.title())
            .param("body", d.body())
            .param("linkPath", d.linkPath())
            .param("digest", d.digest())
            .param("quiet", d.quiet())
            .param("at", at(d.scheduledAt()))
            .update()
        == 1;
  }

  /** 보낼 시각이 지난 줄. 서버가 여러 대여도 같은 줄을 두 번 가져가지 않게 잠가요 (트랜잭션 안에서 불러요) */
  public List<DueDelivery> lockDue(Instant now, int limit) {
    return jdbc.sql(
            """
            select id, user_id, channel, address, type, title, body, link_path, digest, quiet,
                   scheduled_at, attempts
              from deliveries
             where status = 'PENDING' and scheduled_at <= :now
             order by scheduled_at, user_id
             limit :limit
               for update skip locked
            """)
        .param("now", at(now))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new DueDelivery(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    Channel.valueOf(rs.getString("channel")),
                    rs.getString("address"),
                    rs.getBoolean("digest"),
                    rs.getBoolean("quiet"),
                    rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("attempts"),
                    new OutgoingNotification(
                        NotificationType.valueOf(rs.getString("type")),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("link_path"))))
        .list();
  }

  public void markSent(List<UUID> ids, String providerMessageId, Instant now) {
    jdbc.sql(
            """
            update deliveries
               set status = 'SENT', attempts = attempts + 1, provider_message_id = :messageId,
                   sent_at = :now, last_error = null, address = null
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

  /** 다시 보내도 안 되거나 재시도를 다 썼어요. {@code attempted}면 이번에 보내 봤어요 */
  public void markFailed(List<UUID> ids, String error, boolean attempted) {
    jdbc.sql(
            """
            update deliveries
               set status = 'FAILED', last_error = :error, address = null,
                   attempts = attempts + case when :attempted then 1 else 0 end
             where id in (:ids)
            """)
        .param("ids", ids)
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

  /** 새로 만들 발송 줄 */
  public record NewDelivery(
      UUID id,
      UUID userId,
      Channel channel,
      String address,
      NotificationType type,
      String dedupKey,
      String title,
      String body,
      String linkPath,
      boolean digest,
      boolean quiet,
      Instant scheduledAt) {}

  /** 보낼 차례가 된 발송 줄 1개 */
  public record DueDelivery(
      UUID id,
      UUID userId,
      Channel channel,
      String address,
      boolean digest,
      boolean quiet,
      Instant scheduledAt,
      int attempts,
      OutgoingNotification notification) {}
}
