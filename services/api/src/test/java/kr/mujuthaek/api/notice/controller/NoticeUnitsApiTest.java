package kr.mujuthaek.api.notice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.pagination.CursorCodec;
import kr.mujuthaek.api.common.pagination.CursorKey;
import kr.mujuthaek.api.notice.NoticeApiFixture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class NoticeUnitsApiTest {

  private final NoticeApiFixture f = new NoticeApiFixture();
  private final MockMvc mvc = f.mvc(new NoticeController(f.queries));

  /** 예시의 201호를 202호·203호로 늘린 공고 (203호는 다른 건물). */
  private UUID threeUnits() {
    return f.notice(
        p -> {
          ObjectNode detail = (ObjectNode) p.get("notice");
          ArrayNode groups = (ArrayNode) detail.get("supplyGroups");
          ObjectNode first = (ObjectNode) groups.get(0);
          for (String unit : List.of("202", "203")) {
            ObjectNode copy = first.deepCopy();
            copy.put("id", "unit-" + unit);
            copy.put("unitLabel", unit + "호");
            if (unit.equals("203")) {
              copy.put("locationId", "building-b");
            }
            groups.add(copy);
          }
        });
  }

  @Test
  void listsUnitsWithAddressAndPrices() throws Exception {
    UUID id = threeUnits();

    mvc.perform(get("/api/v1/notices/{id}/units", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].unitLabel").value("201호"))
        .andExpect(jsonPath("$.items[0].siteId").value("building-a"))
        .andExpect(jsonPath("$.items[0].address").value("서울특별시 종로구 가상로 1"))
        .andExpect(jsonPath("$.items[0].floor").value("2층"))
        .andExpect(jsonPath("$.items[0].houseType").value("20A"))
        .andExpect(jsonPath("$.items[0].depositWon").value(1000000))
        .andExpect(jsonPath("$.items[0].rentWon").value(100000));
  }

  @Test
  void pagesAndFiltersBySite() throws Exception {
    UUID id = threeUnits();

    List<String> labels = new ArrayList<>();
    String cursor = null;
    do {
      var request = get("/api/v1/notices/{id}/units", id).param("size", "2");
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      String body = mvc.perform(request).andReturn().getResponse().getContentAsString();
      labels.addAll(JsonPath.read(body, "$.items[*].unitLabel"));
      cursor = JsonPath.read(body, "$.nextCursor");
    } while (cursor != null);
    assertThat(labels).containsExactly("201호", "202호", "203호");

    mvc.perform(get("/api/v1/notices/{id}/units", id).param("siteId", "building-b"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unitLabel").value("203호"));
  }

  @Test
  void noticesWithoutUnitsGiveAnEmptyList() throws Exception {
    UUID id = f.notice(p -> ((ObjectNode) p.get("notice")).putArray("supplyGroups"));

    mvc.perform(get("/api/v1/notices/{id}/units", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void tamperedCursorIsInvalid() throws Exception {
    UUID id = threeUnits();
    String bad = CursorCodec.encode(new CursorKey("UNITS", "많이", id));

    mvc.perform(get("/api/v1/notices/{id}/units", id).param("cursor", bad))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    mvc.perform(get("/api/v1/notices/{id}/units", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
