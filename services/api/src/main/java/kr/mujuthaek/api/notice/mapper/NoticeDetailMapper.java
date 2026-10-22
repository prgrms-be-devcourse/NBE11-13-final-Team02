package kr.mujuthaek.api.notice.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.AreaSummary;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Condition;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.FileLink;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.FollowUp;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.HeldItem;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Schedule;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Site;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Summary;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.UnitType;
import kr.mujuthaek.api.notice.dto.response.NoticeUnitResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 저장한 extraction.completed payload(상세 notice: contracts/schemas/extracted-notice.v1.json)에서 상세 응답
 * 묶음을 만들어요.
 *
 * <p>칸이 없으면 빈 목록·null로 둬요 (검수 대기 등으로 상세가 빠진 버전도 있어요).
 *
 * <p>원문 확인 전까지 보류한 항목(quality.heldItems)은 보여 주지 않아요: 그 일정·주택형·호·위치·첨부를 빼고, 위치 칸 하나만 보류했으면 그 칸만 비워요.
 * 보류한 자격 조건은 확인 필요(inReview)로 표시해요. 무엇을 뺐는지는 heldItems로 알려요.
 */
public final class NoticeDetailMapper {

  private NoticeDetailMapper() {}

  public static NoticeDetailResponse map(
      NoticeDetailResponse.Header header,
      JsonNode payload,
      List<AreaSummary> areaSummaries,
      List<FollowUp> followUps,
      int version,
      boolean reviewed,
      OffsetDateTime updatedAt,
      LocalDate today) {
    JsonNode detail = payload.path("notice");
    List<UnitType> unitTypes = unitTypes(detail);
    return new NoticeDetailResponse(
        header,
        summary(payload, detail, unitTypes),
        schedules(detail, today),
        sites(detail),
        unitTypes,
        conditions(payload, detail),
        files(detail),
        heldItems(detail),
        areaSummaries,
        followUps,
        hasUnits(detail),
        version,
        reviewed,
        updatedAt);
  }

  static Summary summary(JsonNode payload, JsonNode detail, List<UnitType> unitTypes) {
    BigDecimal min = null;
    BigDecimal max = null;
    for (UnitType t : unitTypes) {
      if (t.exclusiveM2() != null) {
        min = min == null || t.exclusiveM2().compareTo(min) < 0 ? t.exclusiveM2() : min;
        max = max == null || t.exclusiveM2().compareTo(max) > 0 ? t.exclusiveM2() : max;
      }
    }
    boolean countsHeld = held(detail, "/notice/counts");
    Integer total = countsHeld ? null : offered(detail.path("notice").path("counts"));
    // 공고에 적힌 수가 없을 때만 주택형별 합을 써요. 공고에 적힌 수를 보류했거나 보류한 공급 묶음이 있으면 합이 다른 값일 수 있어 쓰지 않아요
    if (total == null && !countsHeld && !unitTypes.isEmpty() && !held(detail, "/supplyGroups")) {
      total = unitTypes.stream().mapToInt(t -> t.supplyCount() == null ? 0 : t.supplyCount()).sum();
    }
    return new Summary(
        text(payload.path("summary")),
        held(detail, "/notice/targetGroups")
            ? List.of()
            : strings(detail.path("notice").path("targetGroups")),
        total,
        min,
        max);
  }

  /** 후속 안내 한 건. schedules는 후속 안내 글의 일정(상세의 schedules와 같은 모양)이에요. */
  public static FollowUp followUp(
      String title, String detailUrl, LocalDate postedOn, JsonNode schedules, LocalDate today) {
    return new FollowUp(
        title,
        detailUrl,
        postedOn,
        schedules(JsonNodeFactory.instance.objectNode().set("schedules", schedules), today));
  }

  static List<Schedule> schedules(JsonNode detail, LocalDate today) {
    List<Schedule> out = new ArrayList<>();
    JsonNode all = detail.path("schedules");
    for (int i = 0; i < all.size(); i++) {
      if (held(detail, "/schedules/" + i)) {
        continue;
      }
      JsonNode s = all.get(i);
      String starts = text(s.path("startsAt").path("value"));
      String ends = text(s.path("endsAt").path("value"));
      out.add(
          new Schedule(
              text(s.path("id")),
              text(s.path("rawLabel")),
              strings(s.path("kinds")),
              starts,
              ends,
              text(s.path("description")),
              text(s.path("applicationUrl")),
              within(today, starts, ends)));
    }
    return out;
  }

  /** 단지·건물. 위치 전체를 보류했으면 빼고, 이름·주소·지역 코드 중 보류한 칸만 비워요. */
  static List<Site> sites(JsonNode detail) {
    List<Site> out = new ArrayList<>();
    JsonNode all = detail.path("locations");
    for (int i = 0; i < all.size(); i++) {
      String at = "/locations/" + i;
      if (heldWhole(detail, at)) {
        continue;
      }
      JsonNode l = all.get(i);
      String name = held(detail, at + "/complexName") ? null : text(l.path("complexName"));
      if (name == null && !held(detail, at + "/buildingName")) {
        name = text(l.path("buildingName"));
      }
      String code = held(detail, at + "/regionCode") ? null : text(l.path("regionCode"));
      out.add(
          new Site(
              text(l.path("id")),
              text(l.path("kind")),
              name,
              held(detail, at + "/address") ? null : text(l.path("address")),
              code == null || code.length() < 5 ? code : code.substring(0, 5)));
    }
    return out;
  }

  /**
   * 주택형별 묶음: 같은 주택형의 공급 묶음을 합쳐 면적·보증금·월임대료(처음 값)와 공급 호수(합)를 보여줘요. 보류한 공급 묶음이 하나라도 있는 주택형은 빼요(합친
   * 호수·가격이 틀린 값이 되지 않게).
   */
  static List<UnitType> unitTypes(JsonNode detail) {
    Map<String, UnitType> byType = new LinkedHashMap<>();
    Set<String> heldTypes = new HashSet<>();
    JsonNode groups = detail.path("supplyGroups");
    for (int i = 0; i < groups.size(); i++) {
      JsonNode g = groups.get(i);
      String type = text(g.path("housingTypeCode"));
      if (type == null) {
        continue;
      }
      if (held(detail, "/supplyGroups/" + i)) {
        heldTypes.add(type);
        continue;
      }
      Integer count = offered(g.path("counts"));
      if (count == null && "HOUSING_UNIT".equals(text(g.path("itemKind")))) {
        count = 1; // 호 하나가 공급 묶음 하나
      }
      UnitType next =
          new UnitType(
              type,
              strings(g.path("scope").path("targetGroups")),
              decimal(g.path("exclusiveAreaSqm")),
              price(g, "DEPOSIT"),
              price(g, "MONTHLY_RENT"),
              count);
      byType.merge(
          type,
          next,
          (a, b) ->
              new UnitType(
                  a.houseType(),
                  a.targetGroups(),
                  a.exclusiveM2() != null ? a.exclusiveM2() : b.exclusiveM2(),
                  a.depositWon() != null ? a.depositWon() : b.depositWon(),
                  a.rentWon() != null ? a.rentWon() : b.rentWon(),
                  a.supplyCount() == null && b.supplyCount() == null
                      ? null
                      : (a.supplyCount() == null ? 0 : a.supplyCount())
                          + (b.supplyCount() == null ? 0 : b.supplyCount())));
    }
    heldTypes.forEach(byType::remove);
    return List.copyOf(byType.values());
  }

  /** 자격 조건. 상세에서 보류한 조건(/eligibility/{i}, 같은 순서)은 확인 필요로 표시해요. */
  static List<Condition> conditions(JsonNode payload, JsonNode detail) {
    List<Condition> out = new ArrayList<>();
    JsonNode all = payload.path("conditions");
    for (int i = 0; i < all.size(); i++) {
      JsonNode c = all.get(i);
      out.add(
          new Condition(
              text(c.path("id")),
              text(c.path("category")),
              text(c.path("target")),
              strings(c.path("targetGroups")),
              text(c.path("description")),
              text(c.path("originalText")),
              c.path("inReview").asBoolean(false) || held(detail, "/eligibility/" + i)));
    }
    return out;
  }

  static List<FileLink> files(JsonNode detail) {
    List<FileLink> out = new ArrayList<>();
    JsonNode all = detail.path("documents");
    for (int i = 0; i < all.size(); i++) {
      if (held(detail, "/documents/" + i)) {
        continue;
      }
      JsonNode d = all.get(i);
      String url = text(d.path("originalUrl"));
      if (url != null) {
        out.add(new FileLink(text(d.path("name")), url));
      }
    }
    return out;
  }

  /**
   * 호 단위(HOUSING_UNIT) 공급 묶음. siteId가 있으면 그 단지·건물 것만. 주소는 단지·건물 주소예요. 보류한 호는 빼고, 보류한 위치의 주소는 비워요.
   */
  public static List<NoticeUnitResponse> units(JsonNode detail, String siteId) {
    Map<String, String> addresses = new LinkedHashMap<>();
    JsonNode locations = detail.path("locations");
    for (int i = 0; i < locations.size(); i++) {
      String at = "/locations/" + i;
      boolean hidden = heldWhole(detail, at) || held(detail, at + "/address");
      addresses.put(
          text(locations.get(i).path("id")),
          hidden ? null : text(locations.get(i).path("address")));
    }
    List<NoticeUnitResponse> out = new ArrayList<>();
    JsonNode groups = detail.path("supplyGroups");
    for (int i = 0; i < groups.size(); i++) {
      JsonNode g = groups.get(i);
      if (held(detail, "/supplyGroups/" + i)) {
        continue;
      }
      String location = text(g.path("locationId"));
      if (!"HOUSING_UNIT".equals(text(g.path("itemKind")))
          || (siteId != null && !siteId.equals(location))) {
        continue;
      }
      out.add(
          new NoticeUnitResponse(
              text(g.path("id")),
              location,
              addresses.get(location),
              text(g.path("unitLabel")),
              text(g.path("floor")),
              text(g.path("housingTypeCode")),
              decimal(g.path("exclusiveAreaSqm")),
              price(g, "DEPOSIT"),
              price(g, "MONTHLY_RENT")));
    }
    return out;
  }

  public static boolean hasUnits(JsonNode detail) {
    for (JsonNode g : detail.path("supplyGroups")) {
      if ("HOUSING_UNIT".equals(text(g.path("itemKind")))) {
        return true;
      }
    }
    return false;
  }

  /** 원문 확인 전까지 보여 주지 않는 항목. */
  static List<HeldItem> heldItems(JsonNode detail) {
    List<HeldItem> out = new ArrayList<>();
    for (JsonNode h : detail.path("quality").path("heldItems")) {
      String target = text(h.path("target"));
      String section = target == null ? null : target.split("/", 3)[target.startsWith("/") ? 1 : 0];
      out.add(new HeldItem(section, target, text(h.path("description"))));
    }
    return out;
  }

  /** 이 위치(pointer)가 보류 범위와 겹치는지: 그 항목 자체, 그 위 섹션, 그 안의 칸 중 하나라도 보류됨. */
  private static boolean held(JsonNode detail, String pointer) {
    return NoticeContent.held(detail.path("quality").path("heldItems"), pointer);
  }

  /** 이 위치 전체가 보류됐는지: 그 항목 자체나 그 위 섹션이 보류됨 (안의 칸 하나만 보류한 경우는 아님). */
  private static boolean heldWhole(JsonNode detail, String pointer) {
    for (JsonNode h : detail.path("quality").path("heldItems")) {
      String target = h.path("target").asString("");
      if (!target.isEmpty() && (pointer.equals(target) || pointer.startsWith(target + "/"))) {
        return true;
      }
    }
    return false;
  }

  /** 모집 수 (OFFERED) 합. 없으면 null. */
  private static Integer offered(JsonNode counts) {
    Integer total = null;
    for (JsonNode c : counts) {
      if ("OFFERED".equals(text(c.path("kind"))) && c.path("value").isNumber()) {
        total = (total == null ? 0 : total) + c.path("value").asInt();
      }
    }
    return total;
  }

  static Long price(JsonNode group, String kind) {
    for (JsonNode p : group.path("prices")) {
      if (kind.equals(text(p.path("kind"))) && p.path("amountKrw").isNumber()) {
        return p.path("amountKrw").asLong();
      }
    }
    return null;
  }

  private static boolean within(LocalDate today, String starts, String ends) {
    LocalDate from = day(starts);
    LocalDate to = day(ends);
    if (from == null && to == null) {
      return false;
    }
    return (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to));
  }

  /** "2026-10-01" 또는 "2026-10-01T10:00:00+09:00"의 날짜. */
  private static LocalDate day(String value) {
    return value == null || value.length() < 10 ? null : LocalDate.parse(value.substring(0, 10));
  }

  static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
  }

  static BigDecimal decimal(JsonNode node) {
    return node.isNumber() ? node.decimalValue() : null;
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array) {
      out.add(v.asString());
    }
    return out;
  }
}
