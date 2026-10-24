package kr.mujuthaek.api.user.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.user.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query("delete from RefreshToken t where t.tokenHash = :tokenHash")
  int deleteByTokenHash(String tokenHash);

  @Modifying
  @Query("delete from RefreshToken t where t.userId = :userId")
  int deleteByUserId(UUID userId);

  @Modifying
  @Query("delete from RefreshToken t where t.expiresAt <= :now")
  int deleteExpired(Instant now);
}
