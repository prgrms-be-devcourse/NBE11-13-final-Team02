package kr.mujuthaek.api.user.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** account.notification_settings: 알림 종류별 켬·끔과 조용한 시간. 줄이 없으면 기본값(모두 켬, 조용한 시간 끔)이에요. */
@Repository
public class NotificationSettingRepository {

  private final JdbcClient jdbc;

  public NotificationSettingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Settings(
      boolean newNotice,
      boolean newSupport,
      boolean deadline,
      boolean correction,
      boolean followUp,
      boolean quietEnabled) {

    public static final Settings DEFAULT = new Settings(true, true, true, true, true, false);
  }

  public Optional<Settings> find(UUID userId) {
    return jdbc.sql(
            """
            select new_notice, new_support, deadline, correction, follow_up, quiet_enabled
              from account.notification_settings where user_id = :u
            """)
        .param("u", userId)
        .query((rs, i) -> settings(rs))
        .optional();
  }

  /** 줄이 있는 회원만 담아요 (없으면 기본값으로 보세요) */
  public Map<UUID, Settings> findAll(Collection<UUID> userIds) {
    Map<UUID, Settings> out = new HashMap<>();
    if (userIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            select user_id, new_notice, new_support, deadline, correction, follow_up, quiet_enabled
              from account.notification_settings where user_id in (:ids)
            """)
        .param("ids", userIds)
        .query(
            rs -> {
              out.put(rs.getObject("user_id", UUID.class), settings(rs));
            });
    return out;
  }

  public void save(UUID userId, Settings s, Instant now) {
    jdbc.sql(
            """
            insert into account.notification_settings
                (user_id, new_notice, new_support, deadline, correction, follow_up, quiet_enabled, updated_at)
            values (:u, :newNotice, :newSupport, :deadline, :correction, :followUp, :quiet, :now)
            on conflict (user_id) do update set new_notice = excluded.new_notice,
                new_support = excluded.new_support, deadline = excluded.deadline,
                correction = excluded.correction, follow_up = excluded.follow_up,
                quiet_enabled = excluded.quiet_enabled, updated_at = excluded.updated_at
            """)
        .param("u", userId)
        .param("newNotice", s.newNotice())
        .param("newSupport", s.newSupport())
        .param("deadline", s.deadline())
        .param("correction", s.correction())
        .param("followUp", s.followUp())
        .param("quiet", s.quietEnabled())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }

  private static Settings settings(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Settings(
        rs.getBoolean("new_notice"),
        rs.getBoolean("new_support"),
        rs.getBoolean("deadline"),
        rs.getBoolean("correction"),
        rs.getBoolean("follow_up"),
        rs.getBoolean("quiet_enabled"));
  }
}
