package kr.mujuthaek.api.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.dto.response.TokenResponse;
import kr.mujuthaek.api.user.security.RefreshTokenCookies;
import kr.mujuthaek.api.user.service.AuthTokenService;
import kr.mujuthaek.api.user.service.TokenPair;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

  private final AuthTokenService tokens;
  private final RefreshTokenCookies cookies;

  AuthController(AuthTokenService tokens, RefreshTokenCookies cookies) {
    this.tokens = tokens;
    this.cookies = cookies;
  }

  /**
   * access token 발급·재발급. refresh token 쿠키로 확인하고, refresh token도 새 값으로 바꿔 쿠키에 다시 넣어요. 쿠키가 없거나
   * 만료·폐기됐으면 401 INVALID_REFRESH_TOKEN (다시 로그인).
   */
  @PostMapping("/refresh")
  TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
    String raw =
        cookies
            .read(request)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));
    TokenPair pair;
    try {
      pair = tokens.refresh(raw);
    } catch (CustomException e) {
      cookies.clear(response);
      throw e;
    }
    cookies.write(response, pair.refreshToken(), pair.refreshTokenTtl());
    return new TokenResponse(pair.accessToken(), "Bearer", pair.accessTokenTtl().toSeconds());
  }

  /** 로그아웃: 이 기기의 refresh token을 지우고 쿠키도 지워요. 프론트는 메모리의 access token을 버려요. */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    cookies.read(request).ifPresent(tokens::revoke);
    cookies.clear(response);
    return ResponseEntity.noContent().build();
  }
}
