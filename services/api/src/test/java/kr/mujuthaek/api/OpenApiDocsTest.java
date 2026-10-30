package kr.mujuthaek.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 문서(스웨거)가 실제 API와 같은지: 세 그룹(사용자·관리자·내부) 문서를 합치면 서버의 {@code /api/**} 요청 매핑과 빠짐없이 같고, 모든 API에
 * 요약과 태그가 있어요. 로그인·관리자 경로의 자물쇠와 401·403은 OpenApiConfig가 붙여요.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  void 문서가_모든_API를_요약과_함께_담는다() throws Exception {
    Set<String> documented = new TreeSet<>();
    for (String group : List.of("user", "admin", "internal")) {
      for (var path : docs(group).path("paths").properties()) {
        for (var operation : path.getValue().properties()) {
          String name = operation.getKey().toUpperCase() + " " + path.getKey();
          documented.add(name);
          assertThat(operation.getValue().path("summary").asString()).as(name).isNotBlank();
          assertThat(operation.getValue().path("tags").isEmpty()).as(name + "의 태그").isFalse();
        }
      }
    }
    assertThat(documented).isEqualTo(code());
  }

  @Test
  void 로그인_경로는_자물쇠와_401_관리자_경로는_403까지_가진다() throws Exception {
    for (String group : List.of("user", "admin")) {
      JsonNode doc = docs(group);
      assertThat(doc.path("components").path("schemas").has("Problem")).isTrue();
      for (var path : doc.path("paths").properties()) {
        boolean admin = path.getKey().startsWith("/api/admin/");
        if (!admin && !path.getKey().startsWith("/api/users/")) {
          continue;
        }
        for (var operation : path.getValue().properties()) {
          String name = operation.getKey().toUpperCase() + " " + path.getKey();
          JsonNode responses = operation.getValue().path("responses");
          assertThat(operation.getValue().path("security").toString())
              .as(name)
              .contains("accessToken");
          assertThat(responses.has("401")).as(name).isTrue();
          assertThat(responses.has("403")).as(name).isEqualTo(admin);
        }
      }
    }
  }

  @Test
  void 스웨거_화면이_열린다() throws Exception {
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  private JsonNode docs(String group) throws Exception {
    String body =
        mvc.perform(get("/v3/api-docs/" + group))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body);
  }

  /** 서버가 실제로 만드는 {@code /api/**} 요청 매핑: "GET /api/v1/regions" 꼴. */
  private Set<String> code() {
    Set<String> out = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .keySet()
        .forEach(
            info ->
                info.getPathPatternsCondition().getPatternValues().stream()
                    .filter(path -> path.startsWith("/api/"))
                    .forEach(
                        path ->
                            info.getMethodsCondition()
                                .getMethods()
                                .forEach(method -> out.add(method.name() + " " + path))));
    assertThat(out).as("요청 매핑을 못 찾으면 이 시험이 아무것도 안 봐요").hasSizeGreaterThan(100);
    return out;
  }
}
