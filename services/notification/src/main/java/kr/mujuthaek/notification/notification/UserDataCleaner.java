package kr.mujuthaek.notification.notification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** user.withdrawn: 탈퇴한 회원의 발송 줄(안 보낸 것 포함)을 모두 지워요. */
@Component
public class UserDataCleaner {

  private final JdbcClient jdbc;

  public UserDataCleaner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void delete(UUID userId) {
    jdbc.sql("delete from deliveries where user_id = :userId").param("userId", userId).update();
  }
}
