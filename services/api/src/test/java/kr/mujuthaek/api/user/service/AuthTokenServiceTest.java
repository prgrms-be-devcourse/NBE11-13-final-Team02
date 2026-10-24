package kr.mujuthaek.api.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.domain.SocialProvider;
import kr.mujuthaek.api.user.repository.RefreshTokenRepository;
import kr.mujuthaek.api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthTokenServiceTest {

  @Autowired AuthTokenService tokens;
  @Autowired SocialLoginService socialLoginService;
  @Autowired JwtEncoder jwtEncoder;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired JwtProperties props;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;

  private LoginUser login(String kakaoId) {
    return socialLoginService.login(
        new SocialProfile(SocialProvider.KAKAO, kakaoId, "닉" + kakaoId));
  }

  @Test
  void accessTokenCarriesUserIdRoleNickname() {
    LoginUser user = login("3001");

    var jwt = jwtDecoder.decode(tokens.issue(user).accessToken());

    assertThat(jwt.getSubject()).isEqualTo(user.userId().toString());
    assertThat(jwt.getClaimAsString(AuthTokenService.ROLE_CLAIM)).isEqualTo("USER");
    assertThat(jwt.getClaimAsString(AuthTokenService.NICKNAME_CLAIM)).isEqualTo("닉3001");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("mujuthaek-api");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void concurrentRefreshLoserGetsConflictNotServerError() {
    // 두 요청이 같은 쿠키로 동시에 찾기를 통과하고, 먼저 온 쪽이 지운 뒤라 늦은 쪽의 삭제가 0줄인 경우
    var repo = org.mockito.Mockito.mock(RefreshTokenRepository.class);
    var saved =
        new kr.mujuthaek.api.user.domain.RefreshToken(
            java.util.UUID.randomUUID(),
            AuthTokenService.hash("raw"),
            Instant.now().plus(Duration.ofDays(1)),
            Instant.now());
    org.mockito.Mockito.when(repo.findByTokenHash(AuthTokenService.hash("raw")))
        .thenReturn(java.util.Optional.of(saved));
    org.mockito.Mockito.when(repo.deleteByTokenHash(AuthTokenService.hash("raw"))).thenReturn(0);
    var service = new AuthTokenService(jwtEncoder, props, repo, users, Clock.systemUTC());

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).errorCode())
        .isEqualTo(ErrorCode.CONFLICT);
  }

  @Test
  void refreshAndWithdrawalTicketsUseUrlSafeRandomTokens() {
    String a = AuthTokenService.randomToken();
    String b = AuthTokenService.randomToken();

    assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b); // 32바이트 Base64url
  }

  @Test
  void refreshTokenIsStoredOnlyAsHash() {
    String raw = tokens.issue(login("3002")).refreshToken();

    assertThat(refreshTokens.findByTokenHash(raw)).isEmpty();
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(raw))).isPresent();
  }

  @Test
  void refreshUsesCurrentRole() {
    LoginUser user = login("3003");
    String raw = tokens.issue(user).refreshToken();
    users.flush();
    jdbc.update("update account.users set role = 'ADMIN' where id = ?", user.userId());
    entityManager.clear();

    var pair = tokens.refresh(raw);

    assertThat(jwtDecoder.decode(pair.accessToken()).getClaimAsString(AuthTokenService.ROLE_CLAIM))
        .isEqualTo("ADMIN");
    assertThatThrownBy(() -> tokens.refresh(raw))
        .isInstanceOf(CustomException.class); // 회전: 쓴 값은 지워요
  }

  @Test
  void expiredRefreshTokenIsRejectedAndDeleted() {
    LoginUser user = login("3004");
    Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(15)), ZoneOffset.UTC);
    var oldService = new AuthTokenService(jwtEncoder, props, refreshTokens, users, past);
    String raw = oldService.issue(user).refreshToken();

    assertThatThrownBy(() -> tokens.refresh(raw))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(raw))).isEmpty();
  }

  @Test
  void unknownRefreshToken() {
    assertThatThrownBy(() -> tokens.refresh("nope")).isInstanceOf(CustomException.class);
  }

  @Test
  void refreshForDeletedUserIsRejected() {
    LoginUser user = login("3005");
    String raw = tokens.issue(user).refreshToken();
    var other = login("3006");
    users.flush();
    jdbc.update(
        "update account.refresh_tokens set user_id = ? where token_hash = ?",
        other.userId(),
        AuthTokenService.hash(raw));
    jdbc.update(
        "delete from account.users where id = ?",
        other.userId()); // 탈퇴하면 refresh token도 같이 지워져요 (cascade)
    entityManager.clear();

    assertThatThrownBy(() -> tokens.refresh(raw)).isInstanceOf(CustomException.class);
  }

  @Test
  void revokeAndRevokeAll() {
    LoginUser user = login("3007");
    String a = tokens.issue(user).refreshToken();
    String b = tokens.issue(user).refreshToken();
    String c = tokens.issue(user).refreshToken();

    tokens.revoke(a);
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(a))).isEmpty();
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(b))).isPresent();

    tokens.revokeAll(user.userId());
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(c))).isEmpty();
    tokens.revoke("already-gone"); // 없는 값이어도 오류 없이 넘어가요
  }

  @Test
  void deleteExpired() {
    LoginUser user = login("3008");
    Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(30)), ZoneOffset.UTC);
    new AuthTokenService(jwtEncoder, props, refreshTokens, users, past).issue(user);
    String fresh = tokens.issue(user).refreshToken();

    assertThat(tokens.deleteExpired()).isGreaterThanOrEqualTo(1);
    assertThat(refreshTokens.findByTokenHash(AuthTokenService.hash(fresh))).isPresent();
  }

  @Test
  void shortSecretIsRejected() {
    assertThatThrownBy(
            () ->
                new JwtProperties("short", "iss", Duration.ofMinutes(1), Duration.ofDays(1), false))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> new JwtProperties(null, "iss", Duration.ofMinutes(1), Duration.ofDays(1), false))
        .isInstanceOf(IllegalStateException.class);
  }
}
