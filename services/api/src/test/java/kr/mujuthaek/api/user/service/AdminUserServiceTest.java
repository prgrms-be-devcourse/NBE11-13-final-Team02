package kr.mujuthaek.api.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 관리자 회원 관리: 찾기, 지정·해제와 기록, 자기 권한·마지막 관리자 막기, 해제하면 로그인 끊기. */
class AdminUserServiceTest {

  private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneOffset.UTC);
  private final AdminUserService service =
      new AdminUserService(new AdminUserRepository(jdbc), clock);

  private final UUID admin = UUID.randomUUID();
  private final UUID kim = UUID.randomUUID();
  private final UUID lee = UUID.randomUUID();

  @BeforeEach
  void users() {
    jdbc.sql("truncate account.users cascade").update();
    user(admin, "운영자", "ADMIN", "2026-10-01T00:00:00Z");
    user(kim, "김_무주택", "USER", "2026-10-02T00:00:00Z");
    user(lee, "이청년", "USER", "2026-10-03T00:00:00Z");
    jdbc.sql(
            "insert into account.social_accounts (id, user_id, provider, provider_user_id)"
                + " values (:id, :u, 'KAKAO', 'k1')")
        .param("id", UUID.randomUUID())
        .param("u", kim)
        .update();
    jdbc.sql(
            "insert into account.refresh_tokens (id, user_id, token_hash, expires_at)"
                + " values (:id, :u, 'h1', now() + interval '1 day')")
        .param("id", UUID.randomUUID())
        .param("u", kim)
        .update();
  }

  private void user(UUID id, String nickname, String role, String at) {
    jdbc.sql(
            "insert into account.users (id, nickname, role, created_at, updated_at)"
                + " values (:id, :n, :r, cast(:at as timestamptz), now())")
        .param("id", id)
        .param("n", nickname)
        .param("r", role)
        .param("at", at)
        .update();
  }

  @Test
  void 닉네임_일부나_ID로_찾고_권한으로_거른다() {
    var all = service.search(null, null, 0);
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).extracting(u -> u.nickname()).containsExactly("이청년", "김_무주택", "운영자");

    // 「_」는 아무 글자가 아니라 글자 그대로 찾아요
    assertThat(service.search("_무", null, 0).items())
        .singleElement()
        .satisfies(u -> assertThat(u.providers()).containsExactly("KAKAO"));
    assertThat(service.search(lee.toString(), null, 0).items())
        .extracting(u -> u.id())
        .containsExactly(lee);
    assertThat(service.search(null, "ADMIN", 0).items())
        .extracting(u -> u.id())
        .containsExactly(admin);
    assertThat(service.findAll(List.of(kim, UUID.randomUUID())))
        .extracting(u -> u.id())
        .containsExactly(kim);
  }

  @Test
  void 관리자로_지정하고_해제하면_기록이_남고_해제할_때_로그인을_끊는다() {
    assertThat(service.changeRole(kim, "ADMIN", admin).role()).isEqualTo("ADMIN");
    assertThat(service.changeRole(kim, "USER", admin).role()).isEqualTo("USER");
    service.changeRole(kim, "USER", admin); // 이미 USER면 기록을 더 남기지 않아요

    assertThat(service.roleChanges(30))
        .extracting(c -> c.fromRole() + "→" + c.toRole() + " by " + c.changedByNickname())
        .containsExactly("ADMIN→USER by 운영자", "USER→ADMIN by 운영자");
    assertThat(
            jdbc.sql("select count(*) from account.refresh_tokens where user_id = :u")
                .param("u", kim)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void 내_권한과_마지막_관리자는_바꿀_수_없다() {
    assertThatThrownBy(() -> service.changeRole(admin, "USER", admin))
        .isInstanceOfSatisfying(
            CustomException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    // 다른 관리자가 하나뿐인 관리자를 해제하려는 경우(예: 관리자 A가 B를 지정했다가, B만 남은 상태)
    assertThatThrownBy(() -> service.changeRole(admin, "USER", kim))
        .isInstanceOfSatisfying(
            CustomException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    assertThatThrownBy(() -> service.changeRole(UUID.randomUUID(), "ADMIN", admin))
        .isInstanceOfSatisfying(
            CustomException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
