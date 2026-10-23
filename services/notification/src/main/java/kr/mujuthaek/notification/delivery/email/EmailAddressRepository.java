package kr.mujuthaek.notification.delivery.email;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmailAddressRepository {

  private final JdbcClient jdbc;

  public EmailAddressRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 보낼 수 있는 주소: 인증했고 반송·스팸 신고 상태가 아니에요 */
  public Optional<String> findDeliverable(UUID userId) {
    return jdbc.sql(
            """
            select email from email_addresses
             where user_id = :userId and verified_at is not null and status = 'ACTIVE'
            """)
        .param("userId", userId)
        .query(String.class)
        .optional();
  }
}
