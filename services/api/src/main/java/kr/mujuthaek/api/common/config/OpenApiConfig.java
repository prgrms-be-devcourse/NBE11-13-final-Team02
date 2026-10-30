package kr.mujuthaek.api.common.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.security.Principal;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import kr.mujuthaek.api.common.exception.ErrorCode;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 문서(스웨거). 로컬에서 띄우고 http://localhost:8080/swagger-ui.html 에서 봐요 (JSON은 {@code
 * /v3/api-docs/user} 등).
 *
 * <ul>
 *   <li>설명은 컨트롤러가 구현하는 {@code spec} 패키지의 {@code ...ApiSpec} 인터페이스에 있어요. 컨트롤러에는 경로와 구현만 남겨요
 *   <li>그룹: 사용자({@code /api/v1}·{@code /api/users}·{@code /api/auth}), 관리자({@code /api/admin}),
 *       내부({@code /api/internal})
 *   <li>자물쇠(access token)는 손으로 달지 않고 코드에서 읽어요: 로그인 사용자를 받는 API({@code @AuthenticationPrincipal})와
 *       로그인이 필요한 경로(SecurityConfig와 같은 {@code /api/users/**}·{@code /api/admin/**})
 *   <li>켜고 끄기는 application.yml의 {@code springdoc} (운영은 꺼요)
 * </ul>
 */
@Configuration
class OpenApiConfig {

  private static final String BEARER = "accessToken";
  private static final String PROBLEM = "Problem";
  private static final String ADMIN_PATH = "/api/admin/";
  private static final String INTERNAL_PATH = "/api/internal/";
  private static final List<String> LOGIN_PATHS = List.of("/api/users/", ADMIN_PATH);

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("무주택자들 API")
                .version("v1")
                .description(
                    """
                    공공 주거·창업지원 공고와 자격 판정 API예요.

                    - 로그인(로컬): 브라우저에서 `/oauth2/authorization/kakao`(naver · google)로 로그인하면 refresh token 쿠키가 생겨요. \
                    `POST /api/auth/refresh`로 받은 accessToken을 오른쪽 위 Authorize에 넣어요.
                    - 로그인(개발 서버): 로그인이 프론트 주소로 돌아가서, dev 프론트에서 로그인한 뒤 그 탭의 개발자 도구에서 \
                    `fetch('/api/auth/refresh', {method: 'POST', credentials: 'include'})`로 받은 accessToken을 넣어요.
                    - 자물쇠가 붙은 API는 access token을 읽어요. `/api/users/**`는 로그인한 회원, `/api/admin/**`는 관리자만 부를 수 있어요.
                    - 오류는 모두 Problem Details(`application/problem+json`)이고 `code`로 구분해요 (예: `NOTICE_NOT_FOUND`).
                    - API마다 적지 않은 공통 오류
                      - 경로·쿼리 값이나 요청 본문(JSON)의 형식이 틀리면(예: UUID·enum 값이 아님, JSON 문법 오류) 400 `VALIDATION_FAILED`예요.
                      - Authorization 헤더의 access token이 만료됐거나 잘못됐으면, 로그인 없이 되는 API(자물쇠가 붙은 공고 목록 등)도 \
                    401 `UNAUTHENTICATED`예요. `POST /api/auth/refresh`로 새 토큰을 받아 다시 불러요.
                      - 이용 정지된 회원의 쓰기 요청(GET이 아닌 요청)은 403 `ACCOUNT_SUSPENDED`, \
                    탈퇴한 회원의 남은 토큰으로 쓰면 401 `UNAUTHENTICATED`예요.
                    """))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("POST /api/auth/refresh로 받은 accessToken")));
  }

  @Bean
  GroupedOpenApi userApi() {
    return GroupedOpenApi.builder()
        .group("user")
        .displayName("사용자 API")
        .pathsToMatch("/api/v1/**", "/api/users/**", "/api/auth/**")
        .build();
  }

  @Bean
  GroupedOpenApi adminApi() {
    return GroupedOpenApi.builder()
        .group("admin")
        .displayName("관리자 API")
        .pathsToMatch("/api/admin/**")
        .build();
  }

  @Bean
  GroupedOpenApi internalApi() {
    return GroupedOpenApi.builder()
        .group("internal")
        .displayName("내부 API")
        .pathsToMatch("/api/internal/**")
        .build();
  }

  /**
   * 로그인 사용자를 받는 API({@code @AuthenticationPrincipal}이나 {@link Principal})는 로그인 없이도 부를 수 있는 것까지 자물쇠를
   * 달아요 (로그인하면 결과가 달라져요). 로그인이 꼭 필요한지는 스펙 설명과 401로 적어요.
   */
  @Bean
  GlobalOperationCustomizer bearerWhereLoginUserIsRead() {
    return (operation, handler) -> {
      if (Arrays.stream(handler.getMethodParameters())
          .anyMatch(
              p ->
                  p.hasParameterAnnotation(AuthenticationPrincipal.class)
                      || Principal.class.isAssignableFrom(p.getParameterType()))) {
        requireBearer(operation);
      }
      return operation;
    };
  }

  /**
   * 경로로 정해지는 공통 내용(SecurityConfig와 같은 규칙): 로그인이 필요한 경로는 자물쇠와 401, 관리자 경로는 403을 달아요. 응답 본문은 상태 코드로
   * 정해요: 오류(4xx·5xx)는 모두 Problem Details, 204·304는 본문 없음. 그래서 스펙에는 상태 코드와 설명만 적어요. 내부 API는 오류도 상태
   * 코드만 보내요(InternalTrafficController).
   */
  @Bean
  GlobalOpenApiCustomizer commonResponses() {
    return api -> {
      if (api.getPaths() == null) {
        return;
      }
      if (api.getComponents() == null) {
        api.setComponents(new Components());
      }
      api.getComponents().addSchemas(PROBLEM, problemSchema());
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperations()
                      .forEach(
                          operation -> {
                            if (LOGIN_PATHS.stream().anyMatch(path::startsWith)) {
                              requireBearer(operation);
                              addError(operation, ErrorCode.UNAUTHENTICATED);
                            }
                            if (path.startsWith(ADMIN_PATH)) {
                              addError(operation, ErrorCode.FORBIDDEN);
                            }
                            boolean bare = path.startsWith(INTERNAL_PATH);
                            operation
                                .getResponses()
                                .forEach(
                                    (status, response) -> bodyByStatus(status, response, bare));
                          }));
    };
  }

  /**
   * 문서를 끈 곳(운영)에서는 스웨거 화면 파일도 404예요. springdoc을 꺼도 스프링 부트 기본 {@code /webjars/**} 매핑이 화면 껍데기를 내줘서, 더
   * 구체적인 경로에 파일 위치 없는 처리기를 걸어 막아요.
   */
  @Bean
  @ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "false")
  WebMvcConfigurer hideSwaggerUiFiles() {
    return new WebMvcConfigurer() {
      @Override
      public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webjars/swagger-ui/**");
      }
    };
  }

  /**
   * 응답·요청 타입 이름이 겹치지 않게, 중첩 타입은 바깥 타입 이름을 앞에 붙여요 (NoticeSearchResponse.Item →
   * NoticeSearchResponseItem). 스웨거는 기본으로 클래스 이름만 써서, 여러 응답의 Item·Counts 같은 타입이 서로를 덮어써요. 이름만 정하고 다음
   * 변환기로 넘겨요.
   */
  @Bean
  ModelConverter nestedTypeNames() {
    return new NestedTypeNames();
  }

  static class NestedTypeNames implements ModelConverter {

    @Override
    public Schema<?> resolve(
        AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      if (type.getName() == null && type.getType() != null) {
        Class<?> cls = Json.mapper().constructType(type.getType()).getRawClass();
        var named = cls.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (cls.getEnclosingClass() != null && (named == null || named.name().isBlank())) {
          type.name(qualifiedName(cls));
        }
      }
      return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }

    private static String qualifiedName(Class<?> cls) {
      Class<?> outer = cls.getEnclosingClass();
      return outer == null ? cls.getSimpleName() : qualifiedName(outer) + cls.getSimpleName();
    }
  }

  private static void requireBearer(Operation operation) {
    if (operation.getSecurity() == null
        || operation.getSecurity().stream().noneMatch(r -> r.containsKey(BEARER))) {
      operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
  }

  private static void addError(Operation operation, ErrorCode code) {
    String status = String.valueOf(code.status().value());
    if (!operation.getResponses().containsKey(status)) {
      operation
          .getResponses()
          .addApiResponse(
              status, new ApiResponse().description(code.name() + " — " + code.message()));
    }
  }

  /** springdoc은 스펙에 적은 모든 응답에 반환 타입을 본문으로 붙여서, 상태 코드에 맞게 다시 정해요. */
  private static void bodyByStatus(String status, ApiResponse response, boolean bare) {
    boolean error = status.startsWith("4") || status.startsWith("5");
    if (status.equals("204") || status.equals("304") || (error && bare)) {
      response.setContent(null);
    } else if (error) {
      response.content(
          new Content()
              .addMediaType(
                  "application/problem+json",
                  new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }
  }

  /** 오류 응답 모양 (common.exception.Problems). code로 화면 문구를 골라요. */
  private static Schema<?> problemSchema() {
    return new ObjectSchema()
        .description("오류 응답 (Problem Details). code로 구분해요")
        .addProperty("type", new StringSchema())
        .addProperty("title", new StringSchema().description("오류 코드의 기본 문구"))
        .addProperty("status", new IntegerSchema())
        .addProperty("detail", new StringSchema().description("이번 오류의 자세한 이유"))
        .addProperty("instance", new StringSchema().description("요청 경로"))
        .addProperty("code", new StringSchema().example("NOTICE_NOT_FOUND"))
        .addProperty(
            "errors",
            new ArraySchema()
                .description(
                    "VALIDATION_FAILED 중 입력 칸 검사(@Valid 본문, 매개변수 검사)가 틀렸을 때만 와요. JSON을 못 읽었거나 형식이 틀렸으면 없고"
                        + " detail만 와요. 입력값은 넣지 않아요")
                .items(
                    new ObjectSchema()
                        .addProperty(
                            "field",
                            new StringSchema()
                                .description("본문 칸 경로 또는 매개변수 이름")
                                .example("applicant.assets.gtWon"))
                        .addProperty(
                            "reason",
                            new StringSchema()
                                .description(
                                    "어긴 검사 이름 (NOT_NULL · NOT_BLANK · SIZE · MIN · MAX · PATTERN …)")
                                .example("MIN"))))
        .addProperty(
            "timestamp",
            new StringSchema().format("date-time").description("처리하지 못한 500(INTERNAL_ERROR)에서만"));
  }
}
