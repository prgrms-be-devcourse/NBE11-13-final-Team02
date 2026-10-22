package kr.mujuthaek.api.user.service;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 로그인한 회원. 요청마다 access token(JWT)에서 만들어요. 회원 ID·닉네임·권한만 담아요 (소셜 계정 원본 정보는 담지 않음).
 *
 * <p>다른 모듈 컨트롤러에서 {@code @AuthenticationPrincipal LoginUser user}로 받아 {@link #userId()}를 써요.
 *
 * @param nickname 없을 수 있어요
 * @param role USER 또는 ADMIN
 */
public record LoginUser(UUID userId, String nickname, String role)
    implements OAuth2User, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  public boolean isAdmin() {
    return "ADMIN".equals(role);
  }

  @Override
  public String getName() {
    return userId.toString();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return Map.of("userId", userId.toString());
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
