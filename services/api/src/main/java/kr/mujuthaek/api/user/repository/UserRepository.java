package kr.mujuthaek.api.user.repository;

import java.util.UUID;
import kr.mujuthaek.api.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

  /** 회원 삭제. 소셜 계정·refresh token·탈퇴 티켓은 DB의 on delete cascade로 같이 지워져요. */
  @Modifying(clearAutomatically = true)
  @Query("delete from User u where u.id = :id")
  int deleteUser(UUID id);
}
