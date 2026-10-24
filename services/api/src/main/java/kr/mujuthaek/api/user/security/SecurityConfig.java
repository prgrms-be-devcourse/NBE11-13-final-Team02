package kr.mujuthaek.api.user.security;

import java.util.List;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.service.JwtProperties;
import kr.mujuthaek.api.user.service.UserService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 로그인·권한 규칙 (JWT).
 *
 * <ul>
 *   <li>로그인: {@code GET /oauth2/authorization/kakao} → 카카오 → {@code /login/oauth2/code/kakao} →
 *       refresh token 쿠키를 받고 프론트로 돌아가요 (LoginSuccessHandler)
 *   <li>access token: {@code POST /api/auth/refresh}로 받아 {@code Authorization: Bearer}로 보내요
 *   <li>{@code /api/admin/**}는 ADMIN, {@code /api/users/**}는 로그인한 회원만. 나머지(공고·혜택 조회 등)는 누구나
 *   <li>서버는 로그인 상태를 저장하지 않아요(STATELESS). 카카오 로그인 중간의 state만 잠깐 세션에 둬요
 *   <li>CSRF는 끄요: 인증은 Bearer 헤더라 다른 사이트가 대신 보낼 수 없고, 쿠키를 쓰는 재발급·로그아웃은 SameSite=Lax + POST라 다른 사이트에서
 *       실리지 않아요
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({LoginProperties.class, JwtProperties.class})
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SocialOAuth2UserService socialUserService,
      LoginSuccessHandler loginSuccessHandler,
      LoginFailureHandler loginFailureHandler,
      LoginUserJwtConverter jwtConverter,
      KnownProviderAuthorizationRequestResolver providerResolver,
      ProblemResponses problems,
      UserService userService)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, error) -> problems.write(request, response, ErrorCode.UNAUTHENTICATED);
    AccessDeniedHandler forbidden =
        (request, response, error) -> problems.write(request, response, ErrorCode.FORBIDDEN);
    http.cors(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .logout(logout -> logout.disable()) // 로그아웃은 AuthController (refresh token 폐기)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/users/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2Login(
            login ->
                login
                    .authorizationEndpoint(
                        start -> start.authorizationRequestResolver(providerResolver))
                    .userInfoEndpoint(info -> info.userService(socialUserService))
                    .successHandler(loginSuccessHandler)
                    .failureHandler(loginFailureHandler))
        .oauth2ResourceServer(
            server ->
                server
                    .bearerTokenResolver(bearerTokenResolver())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden))
        .addFilterAfter(
            new WithdrawnUserFilter(userService, problems), BearerTokenAuthenticationFilter.class)
        .exceptionHandling(
            errors ->
                errors
                    .defaultAuthenticationEntryPointFor(
                        unauthorized, PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                    .accessDeniedHandler(forbidden));
    return http.build();
  }

  /**
   * {@code Authorization: Bearer}를 읽는 곳. 재발급·로그아웃({@code /api/auth/**})은 쿠키로만 확인해서 헤더를 읽지 않아요. 프론트가
   * 만료된 access token을 붙여 보내도 재발급이 401로 막히지 않게요 (헤더가 있으면 필터가 permitAll보다 먼저 검증해서 401을 내요).
   */
  static BearerTokenResolver bearerTokenResolver() {
    var bearer = new DefaultBearerTokenResolver();
    return request ->
        request.getRequestURI().startsWith("/api/auth/") ? null : bearer.resolve(request);
  }

  /**
   * 로그인할 때 받은 제공자 토큰(카카오·네이버·구글 access token)을 보관하는 곳. 스프링 기본값은 서버 메모리에 회원마다 계속 쌓아 둬요. 우리는 제공자 API를
   * 다시 부르지 않으니 로그인 중간 세션에만 두고, 로그인이 끝나면 세션과 함께 지워요.
   */
  @Bean
  OAuth2AuthorizedClientRepository authorizedClientRepository() {
    return new HttpSessionOAuth2AuthorizedClientRepository();
  }

  /** 프론트(다른 포트·도메인)에서 쿠키(refresh token)와 Authorization 헤더를 실어 부를 수 있게 프론트 주소만 허용해요. */
  @Bean
  CorsConfigurationSource corsConfigurationSource(LoginProperties props) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(props.frontendUrl()));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
    config.setAllowCredentials(true);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
