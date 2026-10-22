package kr.mujuthaek.api.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.domain.RefreshToken;
import kr.mujuthaek.api.user.domain.User;
import kr.mujuthaek.api.user.repository.RefreshTokenRepository;
import kr.mujuthaek.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 발급·재발급·폐기.
 *
 * <ul>
 *   <li>access token: JWT (HS256). sub = 회원 ID, role, nickname. 서버에 저장하지 않아요
 *   <li>refresh token: 무작위 32바이트. DB에는 SHA-256 해시만. 재발급할 때마다 새 값으로 바꿔요(회전), 쓴 값은 지워요
 * </ul>
 */
@Service
public class AuthTokenService {

  public static final String ROLE_CLAIM = "role";
  public static final String NICKNAME_CLAIM = "nickname";

  private final JwtEncoder jwtEncoder;
  private final JwtProperties props;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public AuthTokenService(
      JwtEncoder jwtEncoder,
      JwtProperties props,
      RefreshTokenRepository refreshTokens,
      UserRepository users) {
    this(jwtEncoder, props, refreshTokens, users, Clock.systemUTC());
  }

  AuthTokenService(
      JwtEncoder jwtEncoder,
      JwtProperties props,
      RefreshTokenRepository refreshTokens,
      UserRepository users,
      Clock clock) {
    this.jwtEncoder = jwtEncoder;
    this.props = props;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.clock = clock;
  }

  /** 소셜 로그인 성공 직후: access + refresh 발급. */
  @Transactional
  public TokenPair issue(LoginUser user) {
    Instant now = clock.instant();
    return new TokenPair(
        accessToken(user, now),
        props.accessTokenTtl(),
        newRefreshToken(user.userId(), now),
        props.refreshTokenTtl());
  }

  /**
   * refresh token으로 새 access token 발급. refresh token도 새로 바꾸고 쓴 값은 지워요. 권한·닉네임은 DB의 지금 값을 써요
   * (ADMIN으로 바꾸면 다음 재발급부터 적용).
   */
  @Transactional(noRollbackFor = CustomException.class) // 만료된 값도 지운 채로 401을 보내요
  public TokenPair refresh(String rawRefreshToken) {
    Instant now = clock.instant();
    RefreshToken saved =
        refreshTokens
            .findByTokenHash(hash(rawRefreshToken))
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));
    refreshTokens.delete(saved);
    if (saved.expired(now)) {
      throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    User user =
        users
            .findById(saved.getUserId())
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));
    var login = new LoginUser(user.getId(), user.getNickname(), user.getRole().name());
    return new TokenPair(
        accessToken(login, now),
        props.accessTokenTtl(),
        newRefreshToken(user.getId(), now),
        props.refreshTokenTtl());
  }

  /** 로그아웃: 이 refresh token만 지워요 (다른 기기 로그인은 유지). 이미 없으면 아무것도 안 해요. */
  @Transactional
  public void revoke(String rawRefreshToken) {
    refreshTokens.deleteByTokenHash(hash(rawRefreshToken));
  }

  /** 모든 기기에서 로그아웃 (탈퇴·권한 회수 때). access token은 남은 시간(최대 accessTokenTtl)까지는 유효해요. */
  @Transactional
  public void revokeAll(UUID userId) {
    refreshTokens.deleteByUserId(userId);
  }

  /** 만료된 refresh token 정리. */
  @Transactional
  public int deleteExpired() {
    return refreshTokens.deleteExpired(clock.instant());
  }

  private String accessToken(LoginUser user, Instant now) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(user.userId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.accessTokenTtl()))
            .claim(ROLE_CLAIM, user.role())
            .claim(NICKNAME_CLAIM, user.nickname() == null ? "" : user.nickname())
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private String newRefreshToken(UUID userId, Instant now) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokens.save(new RefreshToken(userId, hash(raw), now.plus(props.refreshTokenTtl()), now));
    return raw;
  }

  static String hash(String raw) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
