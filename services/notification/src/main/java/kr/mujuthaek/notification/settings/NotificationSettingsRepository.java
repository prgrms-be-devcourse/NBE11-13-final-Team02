package kr.mujuthaek.notification.settings;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationSettingsRepository {

  private final JdbcClient jdbc;

  public NotificationSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 설정 줄이 없는 회원은 알림함에만 남고 어느 채널로도 보내지 않아요 */
  public Optional<NotificationSettings> find(UUID userId) {
    return jdbc.sql(
            """
            select email_enabled, push_enabled, new_notice, new_support, deadline, correction,
                   follow_up, quiet_enabled
              from notification_settings where user_id = :userId
            """)
        .param("userId", userId)
        .query(
            (rs, i) ->
                new NotificationSettings(
                    rs.getBoolean("email_enabled"),
                    rs.getBoolean("push_enabled"),
                    rs.getBoolean("new_notice"),
                    rs.getBoolean("new_support"),
                    rs.getBoolean("deadline"),
                    rs.getBoolean("correction"),
                    rs.getBoolean("follow_up"),
                    rs.getBoolean("quiet_enabled")))
        .optional();
  }
}
