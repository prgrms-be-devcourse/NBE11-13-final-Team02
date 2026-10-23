package kr.mujuthaek.notification.notification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** user.withdrawn: 탈퇴한 회원의 알림 데이터를 모두 지워요. 아직 안 보낸 발송 줄도 cascade로 같이 사라져요. */
@Component
public class UserDataCleaner {

  private final JdbcClient jdbc;

  public UserDataCleaner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void delete(UUID userId) {
    for (String table :
        new String[] {"notifications", "email_addresses", "notification_settings"}) {
      jdbc.sql("delete from " + table + " where user_id = :userId")
          .param("userId", userId)
          .update();
    }
  }
}
