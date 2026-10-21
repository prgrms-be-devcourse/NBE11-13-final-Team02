package kr.mujuthaek.api.notice.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Condition;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.FileLink;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Schedule;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Site;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Summary;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.UnitType;
import tools.jackson.databind.JsonNode;

/**
 * 저장한 extraction.completed payload(상세 notice: contracts/schemas/extracted-notice.v1.json)에서 상세 응답
 * 묶음을 만들어요.
 *
 * <p>칸이 없으면 빈 목록·null로 둬요 (검수 대기 등으로 상세가 빠진 버전도 있어요).
 */
public final class NoticeDetailMapper {

  private NoticeDetailMapper() {}

  public static NoticeDetailResponse map(
      NoticeDetailResponse.Header header,
      JsonNode payload,
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
        conditions(payload),
        files(detail),
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
    Integer total = offered(detail.path("notice").path("counts"));
    if (total == null && !unitTypes.isEmpty()) {
      total = unitTypes.stream().mapToInt(t -> t.supplyCount() == null ? 0 : t.supplyCount()).sum();
    }
    return new Summary(
        text(payload.path("summary")),
        strings(detail.path("notice").path("targetGroups")),
        total,
        min,
        max);
  }

  static List<Schedule> schedules(JsonNode detail, LocalDate today) {
    List<Schedule> out = new ArrayList<>();
    for (JsonNode s : detail.path("schedules")) {
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

  static List<Site> sites(JsonNode detail) {
    List<Site> out = new ArrayList<>();
    for (JsonNode l : detail.path("locations")) {
      String name = text(l.path("complexName"));
      if (name == null) {
        name = text(l.path("buildingName"));
      }
      String code = text(l.path("regionCode"));
      out.add(
          new Site(
              text(l.path("id")),
              text(l.path("kind")),
              name,
              text(l.path("address")),
              code == null || code.length() < 5 ? code : code.substring(0, 5)));
    }
    return out;
  }

  /** 주택형별 묶음: 같은 주택형의 공급 묶음을 합쳐 면적·보증금·월임대료(처음 값)와 공급 호수(합)를 보여줘요. */
  static List<UnitType> unitTypes(JsonNode detail) {
    Map<String, UnitType> byType = new LinkedHashMap<>();
    for (JsonNode g : detail.path("supplyGroups")) {
      String type = text(g.path("housingTypeCode"));
      if (type == null) {
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
    return List.copyOf(byType.values());
  }

  static List<Condition> conditions(JsonNode payload) {
    List<Condition> out = new ArrayList<>();
    for (JsonNode c : payload.path("conditions")) {
      out.add(
          new Condition(
              text(c.path("id")),
              text(c.path("category")),
              text(c.path("target")),
              strings(c.path("targetGroups")),
              text(c.path("description")),
              text(c.path("originalText")),
              c.path("inReview").asBoolean(false)));
    }
    return out;
  }

  static List<FileLink> files(JsonNode detail) {
    List<FileLink> out = new ArrayList<>();
    for (JsonNode d : detail.path("documents")) {
      String url = text(d.path("originalUrl"));
      if (url != null) {
        out.add(new FileLink(text(d.path("name")), url));
      }
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
