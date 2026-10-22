package kr.mujuthaek.api.notice.mapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import kr.mujuthaek.api.notice.domain.ApplicationStatusCalculator.Window;
import kr.mujuthaek.api.notice.domain.ReviewStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 추출 결과의 내용에서 notice가 쓰는 값을 꺼내요: 내용 해시, 바뀐 섹션, 원본 글 종류. */
public final class NoticeContent {

  /** 상세 공고에서 비교하는 섹션 (바뀌면 NoticeUpdated.changedFields에 이름이 들어가요). */
  static final List<String> SECTIONS =
      List.of(
          "notice",
          "locations",
          "supplyGroups",
          "schedules",
          "eligibility",
          "selectionRules",
          "requiredDocuments",
          "guidance");

  private NoticeContent() {}

  /**
   * 내용 해시. 받을 때마다 달라지는 칸(입력 이벤트 ID, 검증 시각)을 빼고, 객체 칸 순서와 상관없이 같은 내용이면 같은 값이에요. 배열 순서는 의미가 있어(순위·가점
   * 행) 그대로 둬요.
   */
  public static String hash(ExtractionEvent event) {
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, JsonNode> part : comparableParts(event).entrySet()) {
      canonical.append(part.getKey()).append('=');
      write(part.getValue(), canonical);
      canonical.append('\n');
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 이전 공개 버전과 비교해 달라진 섹션 이름. 판정용 조건(conditions)·보류 항목(heldItems)·접수 기간(applicationWindows)·판정
   * 틀(judgment)도 비교해요. 내용 해시에 들어가는 부분은 모두 비교해, 새 버전인데 바뀐 섹션이 비는 일이 없게 해요.
   *
   * @param previousJudgment 이전 버전의 판정 틀 (없으면 null)
   */
  public static List<String> changedSections(
      JsonNode previousSnapshot,
      JsonNode previousConditions,
      JsonNode previousHeldItems,
      JsonNode previousApplicationWindows,
      JsonNode previousJudgment,
      ExtractionEvent current) {
    List<String> changed = new ArrayList<>();
    for (String section : SECTIONS) {
      if (!same(previousSnapshot.path(section), current.notice().path(section))) {
        changed.add(section);
      }
    }
    if (!same(previousConditions, current.conditions())) {
      changed.add("conditions");
    }
    if (!same(previousHeldItems, current.heldItems())) {
      changed.add("heldItems");
    }
    if (!same(previousApplicationWindows, current.applicationWindows())) {
      changed.add("applicationWindows");
    }
    if (!same(previousJudgment, current.judgment())) {
      changed.add("judgment");
    }
    return changed;
  }

  /** 비교에 쓰는 부분. 받을 때마다 달라지는 칸은 빼요. */
  private static Map<String, JsonNode> comparableParts(ExtractionEvent event) {
    Map<String, JsonNode> parts = new TreeMap<>();
    if (event.notice() != null) {
      ObjectNode notice = (ObjectNode) event.notice().deepCopy();
      notice.remove("input"); // eventId 등 받을 때마다 달라짐
      if (notice.path("quality").path("verification") instanceof ObjectNode verification) {
        verification.remove("checkedAt");
      }
      parts.put("notice", notice);
    }
    parts.put("conditions", event.conditions());
    parts.put("heldItems", event.heldItems());
    parts.put("applicationWindows", event.applicationWindows());
    if (event.judgment() != null) { // 판정 틀이 없는 이전 이벤트의 해시는 그대로 두려고 있을 때만 넣어요
      parts.put("judgment", event.judgment());
    }
    return parts;
  }

  private static String canonical(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  /** 객체 칸을 이름순으로 쓴 JSON. 같은 내용이면 같은 글자가 나와요. */
  private static void write(JsonNode node, StringBuilder out) {
    if (node == null || node.isMissingNode()) {
      out.append("null");
    } else if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        sorted.put(entry.getKey(), entry.getValue());
      }
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append('"').append(entry.getKey()).append("\":");
        write(entry.getValue(), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        write(node.get(i), out);
      }
      out.append(']');
    } else {
      out.append(node.toString());
    }
  }

  /** 접수 상태 계산용 구간 (원문 일정의 시각·보류 구간까지 반영). */
  public static List<Window> statusWindows(ExtractionEvent event) {
    return NoticeProjection.statusWindows(
        event.notice(), event.reviewStatus() == ReviewStatus.APPROVED);
  }

  /** 원문에 상시 모집이 명시된 접수 일정(kinds에 APPLICATION, applicationMode=ROLLING)이 있는지. 보류한 일정은 판정에 쓰지 않아요. */
  public static boolean rolling(ExtractionEvent event) {
    return NoticeProjection.rolling(event.notice(), event.reviewStatus() == ReviewStatus.APPROVED);
  }

  /** 보류 범위와 양방향으로 겹치는지. 하위 필드를 보류한 항목도 사용하지 않아요. */
  static boolean held(JsonNode heldItems, String pointer) {
    for (JsonNode item : heldItems) {
      String target = item.path("target").asString("");
      if (!target.isEmpty()
          && (pointer.equals(target)
              || pointer.startsWith(target + "/")
              || target.startsWith(pointer + "/"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 원본 글의 종류와 정정 관계. 수집기가 넣어 준 값(notice.sourceReferences 중 이 원본 글)을 그대로 써요. 상세가 없거나 못 찾으면 일반 공고로
   * 봐요.
   *
   * @param kind NOTICE, CORRECTION(정정 공고), FOLLOW_UP(후속 안내)
   * @param supersedesPostId 정정·후속 안내가 가리키는 원래 공고의 원본 글 ID
   * @param sourceTitle 원본 글 제목 (없으면 null)
   * @param detailUrl 원문 링크 (없으면 null)
   * @param postedOn 게시일 (없으면 null)
   */
  public record Source(
      String kind,
      UUID supersedesPostId,
      String sourceTitle,
      String detailUrl,
      LocalDate postedOn) {

    public static final String NOTICE = "NOTICE";
    public static final String CORRECTION = "CORRECTION";
    public static final String FOLLOW_UP = "FOLLOW_UP";

    public boolean correction() {
      return CORRECTION.equals(kind) && supersedesPostId != null;
    }

    public boolean followUp() {
      return FOLLOW_UP.equals(kind) && supersedesPostId != null;
    }

    /** 정정·후속 안내인데 원래 공고를 가리키지 않음 (계약 UNLINKED_RELATED_NOTICE). 일반 공고로 공개하지 않고 보류해요. */
    public boolean unlinked() {
      return !NOTICE.equals(kind) && supersedesPostId == null;
    }
  }

  public static Source source(ExtractionEvent event) {
    if (event.notice() != null) {
      for (JsonNode reference : event.notice().path("sourceReferences")) {
        if (event
            .rawPostId()
            .toString()
            .equalsIgnoreCase(reference.path("rawPostId").asString(""))) {
          JsonNode supersedes = reference.path("supersedesPostId");
          return new Source(
              reference.path("kind").asString(Source.NOTICE),
              supersedes.isString() ? UUID.fromString(supersedes.asString()) : null,
              textOrNull(reference, "sourceTitle"),
              textOrNull(reference, "detailUrl"),
              date(reference, "postedOn"));
        }
      }
    }
    return new Source(Source.NOTICE, null, null, null, null);
  }

  /** 후속 안내의 일정 (상세 공고의 schedules 그대로). */
  public static JsonNode schedules(ExtractionEvent event) {
    JsonNode schedules = event.notice() == null ? null : event.notice().path("schedules");
    return schedules != null && schedules.isArray()
        ? schedules
        : tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
  }

  private static String textOrNull(JsonNode node, String name) {
    JsonNode value = node.path(name);
    return value.isString() && !value.asString().isBlank() ? value.asString() : null;
  }

  private static LocalDate date(JsonNode node, String name) {
    JsonNode value = node.path(name);
    if (!value.isString()) {
      return null;
    }
    try {
      return LocalDate.parse(value.asString());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static boolean same(JsonNode a, JsonNode b) {
    return Objects.equals(canonical(a), canonical(b));
  }
}
