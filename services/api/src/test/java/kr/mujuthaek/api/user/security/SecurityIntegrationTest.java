package kr.mujuthaek.api.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.user.domain.SocialProvider;
import kr.mujuthaek.api.user.service.AuthTokenService;
import kr.mujuthaek.api.user.service.LoginUser;
import kr.mujuthaek.api.user.service.SocialLoginService;
import kr.mujuthaek.api.user.service.SocialProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired SocialLoginService socialLoginService;
  @Autowired SocialOAuth2UserService socialUserService;
  @Autowired AuthTokenService tokens;
  @Autowired LoginSuccessHandler loginSuccessHandler;
  @Autowired JwtEncoder jwtEncoder;
  @Autowired LoginFailureHandler loginFailureHandler;
  @Autowired RefreshTokenCookies refreshTokenCookies;
  @Autowired LoginProperties loginProperties;
  @Autowired kr.mujuthaek.api.user.service.WithdrawalService withdrawalService;
  @Autowired kr.mujuthaek.api.user.repository.UserRepository users;

  private LoginUser login(String kakaoId) {
    return socialLoginService.login(
        new SocialProfile(SocialProvider.KAKAO, kakaoId, "회원" + kakaoId));
  }

  private String bearer(LoginUser user) {
    return "Bearer " + tokens.issue(user).accessToken();
  }

  private static Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie(RefreshTokenCookies.NAME);
  }

  @Test
  void meWithoutTokenIs401ProblemJson() throws Exception {
    mvc.perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json;charset=UTF-8"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void meWithAccessToken() throws Exception {
    LoginUser user = login("2001");
    mvc.perform(get("/api/users/me").header("Authorization", bearer(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.userId().toString()))
        .andExpect(jsonPath("$.nickname").value("회원2001"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.providers[0]").value("KAKAO"));
  }

  @Test
  void brokenOrExpiredTokenIs401() throws Exception {
    LoginUser user = login("2002");
    var expired =
        JwtClaimsSet.builder()
            .issuer("mujuthaek-api")
            .subject(user.userId().toString())
            .issuedAt(Instant.now().minusSeconds(3600))
            .expiresAt(Instant.now().minusSeconds(60))
            .claim(AuthTokenService.ROLE_CLAIM, "USER")
            .build();
    String expiredToken =
        jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expired))
            .getTokenValue();

    mvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expiredToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminPathNeedsAdminRole() throws Exception {
    LoginUser user = login("2003");
    var admin = new LoginUser(user.userId(), user.nickname(), "ADMIN");

    mvc.perform(get("/api/admin/anything")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/anything").header("Authorization", bearer(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/api/admin/anything").header("Authorization", bearer(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void publicPathsNeedNoLogin() throws Exception {
    // 로그인 없이 열리는지만 봐요. 상태 코드는 DB·RabbitMQ 상태에 따라 200·503이 갈려서 비교하지 않아요
    mvc.perform(get("/actuator/health"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  void loginStartsAtKakao() throws Exception {
    var result =
        mvc.perform(get("/oauth2/authorization/kakao"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(result.getResponse().getRedirectedUrl())
        .startsWith("https://kauth.kakao.com/oauth/authorize")
        .contains("scope=profile_nickname")
        .contains("redirect_uri=http://localhost/login/oauth2/code/kakao");
  }

  @Test
  void loginStartsAtNaverAndGoogle() throws Exception {
    var naver =
        mvc.perform(get("/oauth2/authorization/naver"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(naver.getResponse().getRedirectedUrl())
        .startsWith("https://nid.naver.com/oauth2.0/authorize")
        .contains("redirect_uri=http://localhost/login/oauth2/code/naver");

    var google =
        mvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(google.getResponse().getRedirectedUrl())
        .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
        .contains("scope=profile")
        .doesNotContain("openid")
        .contains("redirect_uri=http://localhost/login/oauth2/code/google");
  }

  @Test
  void naverUserBecomesLoginUser() {
    var naver =
        new DefaultOAuth2User(
            List.of(),
            Map.of("resultcode", "00", "response", Map.of("id", "nv-1", "nickname", "네이버닉")),
            "response");

    var principal = (LoginUser) socialUserService.toLoginUser("naver", naver);

    assertThat(principal.nickname()).isEqualTo("네이버닉");
    assertThat(principal.role()).isEqualTo("USER");
  }

  @Test
  void loginSuccessSetsRefreshCookieAndGoesToFrontend() throws Exception {
    LoginUser user = login("2004");
    var request = new MockHttpServletRequest();
    request.getSession(true); // 카카오 로그인 중간에 쓴 세션
    var response = new MockHttpServletResponse();

    loginSuccessHandler.onAuthenticationSuccess(
        request, response, new TestingAuthenticationToken(user, null));

    assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/");
    assertThat(response.getHeader("Set-Cookie"))
        .startsWith(RefreshTokenCookies.NAME + "=")
        .contains("Path=/api/auth", "HttpOnly", "SameSite=Lax", "Max-Age=1209600");
    assertThat(request.getSession(false)).isNull(); // 로그인이 끝나면 세션을 지워요
  }

  @Test
  void refreshRotatesTokenAndOldOneStopsWorking() throws Exception {
    LoginUser user = login("2005");
    var first = new Cookie(RefreshTokenCookies.NAME, tokens.issue(user).refreshToken());

    MvcResult refreshed =
        mvc.perform(post("/api/auth/refresh").cookie(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(1800))
            .andReturn();
    String access =
        com.jayway.jsonpath.JsonPath.read(
            refreshed.getResponse().getContentAsString(), "$.accessToken");
    Cookie second = refreshCookie(refreshed);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());

    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/refresh").cookie(first)) // 이미 쓴 값은 다시 못 써요
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    mvc.perform(post("/api/auth/refresh").cookie(second)).andExpect(status().isOk());
  }

  @Test
  void refreshWithoutCookieIs401() throws Exception {
    mvc.perform(post("/api/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  void logoutRevokesRefreshToken() throws Exception {
    LoginUser user = login("2006");
    var cookie = new Cookie(RefreshTokenCookies.NAME, tokens.issue(user).refreshToken());

    MvcResult result =
        mvc.perform(post("/api/auth/logout").cookie(cookie))
            .andExpect(status().isNoContent())
            .andReturn();
    assertThat(refreshCookie(result).getMaxAge()).isZero();

    mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent()); // 쿠키가 없어도 204
  }

  @Test
  void kakaoUserBecomesLoginUser() {
    var kakao =
        new DefaultOAuth2User(
            List.of(), Map.of("id", 777L, "properties", Map.of("nickname", "카카오닉")), "id");

    var principal = (LoginUser) socialUserService.toLoginUser("kakao", kakao);

    assertThat(principal.nickname()).isEqualTo("카카오닉");
    assertThat(principal.getName()).isEqualTo(principal.userId().toString());
    assertThat(principal.getAttributes()).containsOnlyKeys("userId");
  }

  // ── 예외 처리 ─────────────────────────────────────────────

  @Test
  void wrongHttpMethodIs405NotServerError() throws Exception {
    mvc.perform(get("/api/auth/refresh"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  void unknownProviderIs404NotServerError() throws Exception {
    mvc.perform(get("/oauth2/authorization/facebook"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void unusableProviderResponseBecomesLoginFailure() {
    var noId =
        new DefaultOAuth2User(
            List.of(),
            Map.of("properties", Map.of("nickname", "닉"), "connected_at", "x"),
            "connected_at");

    assertThatThrownBy(() -> socialUserService.toLoginUser("kakao", noId))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
        .isEqualTo(SocialOAuth2UserService.INVALID_SOCIAL_PROFILE);
  }

  @Test
  void loginFailureGoesToFrontendWithReason() throws Exception {
    var response = new MockHttpServletResponse();
    var error =
        new OAuth2AuthenticationException(
            new org.springframework.security.oauth2.core.OAuth2Error(
                SocialOAuth2UserService.SOCIAL_LOGIN_FAILED));

    loginFailureHandler.onAuthenticationFailure(new MockHttpServletRequest(), response, error);

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:3000/login?error=social&reason=social_login_failed");
  }

  @Test
  void tokenIssueFailureGoesToFrontendInsteadOf500() throws Exception {
    AuthTokenService broken = mock(AuthTokenService.class);
    when(broken.issue(any())).thenThrow(new IllegalStateException("db down"));
    var handler =
        new LoginSuccessHandler(
            broken,
            refreshTokenCookies,
            loginProperties,
            loginFailureHandler,
            withdrawalService,
            mock(SocialUnlinker.class));
    var response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        new MockHttpServletRequest(),
        response,
        new TestingAuthenticationToken(login("2010"), null));

    assertThat(response.getRedirectedUrl())
        .endsWith("/login?error=social&reason=" + LoginSuccessHandler.TOKEN_ISSUE_FAILED);
    assertThat(response.getHeader("Set-Cookie")).isNull(); // refresh token 쿠키를 주지 않아요
  }

  // ── 탈퇴 (재인증 → 회원 삭제 → 소셜 연결 끊기) ─────────────────────

  private LoginSuccessHandler handlerWith(SocialUnlinker unlinker) {
    return new LoginSuccessHandler(
        tokens,
        refreshTokenCookies,
        loginProperties,
        loginFailureHandler,
        withdrawalService,
        unlinker);
  }

  private MockHttpServletResponse finishReauth(
      LoginSuccessHandler handler, WithdrawalPrincipal principal) throws Exception {
    var response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(
        new MockHttpServletRequest(), response, new TestingAuthenticationToken(principal, null));
    return response;
  }

  @Test
  void startWithdrawalReturnsReauthUrl() throws Exception {
    LoginUser user = login("3101");

    mvc.perform(post("/api/users/me/withdrawal").header("Authorization", bearer(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("KAKAO"))
        .andExpect(jsonPath("$.expiresIn").value(600))
        .andExpect(
            jsonPath("$.reauthUrl")
                .value(
                    org.hamcrest.Matchers.startsWith(
                        "http://localhost/oauth2/authorization/kakao?withdraw=")));
    mvc.perform(
            post("/api/users/me/withdrawal")
                .header("Authorization", bearer(user))
                .param("provider", "google"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WITHDRAWAL_PROVIDER_NOT_LINKED"));
    mvc.perform(post("/api/users/me/withdrawal")).andExpect(status().isUnauthorized());
  }

  @Test
  void reauthStartForcesLoginAndMarksSession() throws Exception {
    var naver =
        mvc.perform(get("/oauth2/authorization/naver").param("withdraw", "ticket-1"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(naver.getResponse().getRedirectedUrl())
        .contains("auth_type=reauthenticate")
        .doesNotContain("ticket-1");
    assertThat(WithdrawalSession.ticket(naver.getRequest())).contains("ticket-1");

    var kakao =
        mvc.perform(get("/oauth2/authorization/kakao").param("withdraw", "ticket-2")).andReturn();
    assertThat(kakao.getResponse().getRedirectedUrl()).contains("prompt=login");
    var google =
        mvc.perform(get("/oauth2/authorization/google").param("withdraw", "ticket-3")).andReturn();
    assertThat(google.getResponse().getRedirectedUrl()).contains("prompt=select_account");

    var normal = mvc.perform(get("/oauth2/authorization/kakao")).andReturn(); // 일반 로그인은 그대로
    assertThat(normal.getResponse().getRedirectedUrl()).doesNotContain("prompt=");
    assertThat(WithdrawalSession.ticket(normal.getRequest())).isEmpty();
  }

  @Test
  void withdrawalDeletesUserThenUnlinks() throws Exception {
    LoginUser user = login("3102");
    String refresh = tokens.issue(user).refreshToken();
    String accessBeforeWithdrawal = bearer(user);
    String ticket = withdrawalService.start(user.userId(), null).ticket();
    SocialUnlinker unlinker = mock(SocialUnlinker.class);

    var response =
        finishReauth(
            handlerWith(unlinker),
            new WithdrawalPrincipal(SocialProvider.KAKAO, "3102", ticket, "kakao-token"));

    assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/withdrawn");
    assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0"); // refresh token 쿠키 삭제
    assertThat(users.findById(user.userId())).isEmpty();
    verify(unlinker).unlink(SocialProvider.KAKAO, "kakao-token");
    mvc.perform(post("/api/auth/refresh").cookie(new Cookie(RefreshTokenCookies.NAME, refresh)))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/users/me")
                .header("Authorization", accessBeforeWithdrawal)) // 탈퇴 전에 받은 access token
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unlinkFailureStillCompletesWithdrawal() throws Exception {
    LoginUser user = login("3103");
    String ticket = withdrawalService.start(user.userId(), null).ticket();
    SocialUnlinker unlinker = mock(SocialUnlinker.class);
    doThrow(new IllegalStateException("provider down")).when(unlinker).unlink(any(), any());

    var response =
        finishReauth(
            handlerWith(unlinker),
            new WithdrawalPrincipal(SocialProvider.KAKAO, "3103", ticket, "t"));

    assertThat(response.getRedirectedUrl()).endsWith("/withdrawn");
    assertThat(users.findById(user.userId())).isEmpty();
  }

  @Test
  void reauthWithOtherAccountDoesNotWithdraw() throws Exception {
    LoginUser user = login("3104");
    login("3105");
    String ticket = withdrawalService.start(user.userId(), null).ticket();
    SocialUnlinker unlinker = mock(SocialUnlinker.class);

    var response =
        finishReauth(
            handlerWith(unlinker),
            new WithdrawalPrincipal(SocialProvider.KAKAO, "3105", ticket, "t"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:3000/mypage?withdraw=account_mismatch");
    assertThat(users.findById(user.userId())).isPresent();
    verify(unlinker, never()).unlink(any(), any());
  }

  @Test
  void unknownOrUsedTicketDoesNotWithdraw() throws Exception {
    LoginUser user = login("3106");
    SocialUnlinker unlinker = mock(SocialUnlinker.class);

    var response =
        finishReauth(
            handlerWith(unlinker),
            new WithdrawalPrincipal(SocialProvider.KAKAO, "3106", "made-up", "t"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:3000/mypage?withdraw=expired");
    assertThat(users.findById(user.userId())).isPresent();
    verify(unlinker, never()).unlink(any(), any());
  }

  @Test
  void cancelDuringReauthGoesBackToMypage() throws Exception {
    var request = new MockHttpServletRequest();
    WithdrawalSession.start(request, "ticket");
    var response = new MockHttpServletResponse();
    var denied =
        new OAuth2AuthenticationException(
            new org.springframework.security.oauth2.core.OAuth2Error("access_denied"));

    loginFailureHandler.onAuthenticationFailure(request, response, denied);

    assertThat(response.getRedirectedUrl())
        .isEqualTo("http://localhost:3000/mypage?withdraw=cancelled");
  }

  @Test
  void reauthCallbackBuildsWithdrawalPrincipalWithoutLogin() {
    var naver =
        new DefaultOAuth2User(
            List.of(), Map.of("response", Map.of("id", "nv-reauth", "nickname", "닉")), "response");

    var principal =
        (WithdrawalPrincipal)
            socialUserService.toWithdrawalPrincipal("naver", naver, "ticket", "naver-token");

    assertThat(principal.provider()).isEqualTo(SocialProvider.NAVER);
    assertThat(principal.providerUserId()).isEqualTo("nv-reauth");
    assertThat(principal.toString())
        .doesNotContain("naver-token")
        .doesNotContain("ticket"); // 로그에 토큰·티켓이 안 남게
  }
}
