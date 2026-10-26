package kr.mujuthaek.api.notification.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** notification.notifications: 알림함. 받는 사람마다 한 줄이에요. */
@Repository
public class InboxRepository {

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  public InboxRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /** 새로 넣을 줄 */
  public record NewEntry(
      UUID id,
      UUID userId,
      String type,
      String targetType,
      UUID targetId,
      String title,
      String body,
      String linkPath,
      String dedupKey,
      Instant createdAt) {}

  /** 알림함 한 줄 */
  public record Entry(
      UUID id,
      String type,
      String targetType,
      UUID targetId,
      String title,
      String body,
      String linkPath,
      Instant createdAt,
      Instant readAt) {}

  /** 같은 회원·같은 알림(dedupKey)이 이미 있으면 넣지 않아요. 새로 넣은 줄 수를 돌려줘요 */
  public int addAll(List<NewEntry> entries) {
    if (entries.isEmpty()) {
      return 0;
    }
    MapSqlParameterSource[] rows =
        entries.stream()
            .map(
                e ->
                    new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("userId", e.userId())
                        .addValue("type", e.type())
                        .addValue("targetType", e.targetType())
                        .addValue("targetId", e.targetId())
                        .addValue("title", e.title())
                        .addValue("body", e.body())
                        .addValue("linkPath", e.linkPath())
                        .addValue("dedupKey", e.dedupKey())
                        .addValue("at", at(e.createdAt())))
            .toArray(MapSqlParameterSource[]::new);
    int inserted = 0;
    for (int n :
        batch.batchUpdate(
            """
            insert into notification.notifications
                (id, user_id, type, target_type, target_id, title, body, link_path, dedup_key, created_at)
            values (:id, :userId, :type, :targetType, :targetId, :title, :body, :linkPath, :dedupKey, :at)
            on conflict (user_id, dedup_key) do nothing
            """,
            rows)) {
      inserted += Math.max(n, 0);
    }
    return inserted;
  }

  /** 최근 것부터 {@code limit}개. {@code beforeId}가 있으면 그 줄 다음부터 */
  public List<Entry> page(UUID userId, UUID beforeId, boolean unreadOnly, int limit) {
    return jdbc.sql(
            """
            select id, type, target_type, target_id, title, body, link_path, created_at, read_at
              from notification.notifications
             where user_id = :userId
               and (cast(:beforeId as uuid) is null or id < :beforeId)
               and (not :unreadOnly or read_at is null)
             order by id desc
             limit :limit
            """)
        .param("userId", userId)
        .param("beforeId", beforeId, java.sql.Types.OTHER)
        .param("unreadOnly", unreadOnly)
        .param("limit", limit)
        .query(
            (rs, i) ->
                new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    rs.getString("target_type"),
                    rs.getObject("target_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getString("link_path"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    instant(rs.getObject("read_at", OffsetDateTime.class))))
        .list();
  }

  public long unreadCount(UUID userId) {
    return jdbc.sql(
            "select count(*) from notification.notifications where user_id = :userId and read_at is null")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  /** 읽음으로. 이미 읽었으면 그 시각 그대로예요. 이 회원의 줄이 없으면 false */
  public boolean markRead(UUID userId, UUID id, Instant now) {
    return jdbc.sql(
                """
                update notification.notifications set read_at = coalesce(read_at, :now)
                 where user_id = :userId and id = :id
                """)
            .param("userId", userId)
            .param("id", id)
            .param("now", at(now))
            .update()
        == 1;
  }

  /** 안 읽은 줄을 모두 읽음으로. 바꾼 줄 수를 돌려줘요 */
  public int markAllRead(UUID userId, Instant now) {
    return jdbc.sql(
            """
            update notification.notifications set read_at = :now
             where user_id = :userId and read_at is null
            """)
        .param("userId", userId)
        .param("now", at(now))
        .update();
  }

  public int deleteAll(UUID userId) {
    return jdbc.sql("delete from notification.notifications where user_id = :userId")
        .param("userId", userId)
        .update();
  }

  /** {@code cutoff}보다 오래된 줄을 {@code limit}개까지 지워요 */
  public int deleteOlderThan(Instant cutoff, int limit) {
    return jdbc.sql(
            """
            delete from notification.notifications where id in (
              select id from notification.notifications where created_at < :cutoff limit :limit)
            """)
        .param("cutoff", at(cutoff))
        .param("limit", limit)
        .update();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
