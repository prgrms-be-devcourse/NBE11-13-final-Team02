package kr.mujuthaek.api.notice.service.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notice.client.ExtractionReviewClient;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest.FieldEdit;
import kr.mujuthaek.api.notice.dto.response.AdminReviewCountsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDecisionResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Edit;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Field;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Issue;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.LogEntry;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Source;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse.Item;
import kr.mujuthaek.api.notice.dto.response.AdminReviewStatsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewStatsResponse.Window;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 검수 화면용으로 추출 검수 API 응답을 옮겨요. 이 서버에는 검수 데이터를 저장하지 않아요(추출 서비스 DB가 원본).
 *
 * <p>사유 묶음은 작업 상태로 정해요: IN_REVIEW(아직 발행 안 함) → MUST, PARTIAL(발행했고 일부 칸 보류) → CHECK,
 * INTAKE_REVIEW(원본 자료끼리 맞지 않아 추출 안 함) → FORMAT.
 *
 * <p>신뢰도 숫자는 만들지 않아요. 확인할 칸은 추출 결과의 검증 이슈(quality.reviewIssues)와 보류 칸(quality.heldItems)에서 모으고, 이유는
 * 그 이슈 설명을 그대로 보여줘요.
 */
@Service
public class AdminReviewService {

  public static final List<String> REASONS = List.of("MUST", "CHECK", "FORMAT");
  private static final String PENDING = "PENDING";
  private static final int MAX_FIELDS = 100;
  private static final Map<String, String> SECTIONS =
      Map.of(
          "notice", "공고",
          "locations", "위치",
          "schedules", "일정",
          "supplyGroups", "공급",
          "eligibility", "자격 조건",
          "selectionRules", "선정 기준",
          "requiredDocuments", "서류",
          "guidance", "안내");
  private static final List<String> LABEL_KEYS =
      List.of("name", "rawLabel", "title", "label", "description", "originalText", "id");

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final ExtractionReviewClient client;
  private final Clock clock;

  public AdminReviewService(ExtractionReviewClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  /**
   * 마감이 가까운 검수 대기 (관리자 개요의 「지금 처리할 일」): 신청 마감이 오늘(한국 시간)부터 days일 안이거나 이미 지난 대기 건, 마감이 이른 것부터. 아직 발행
   * 전(MUST)이면 사용자에게 안 보이는 공고예요.
   */
  public List<Item> urgent(int days) {
    if (days < 0 || days > 30) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "days는 0~30 사이예요");
    }
    LocalDate limit = LocalDate.now(clock.withZone(SEOUL)).plusDays(days);
    return items(client.list(PENDING)).stream()
        .filter(i -> i.applyEndsOn() != null && !i.applyEndsOn().isAfter(limit))
        .sorted(Comparator.comparing(Item::applyEndsOn))
        .toList();
  }

  /** 대기 목록. reason이 있으면 그 묶음만. */
  public AdminReviewListResponse pending(String reason) {
    if (reason != null && !REASONS.contains(reason)) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "reason은 MUST·CHECK·FORMAT 중 하나예요");
    }
    List<Item> all = items(client.list(PENDING));
    List<Item> shown =
        reason == null ? all : all.stream().filter(i -> reason.equals(i.reason())).toList();
    return new AdminReviewListResponse(all.size(), shown);
  }

  public AdminReviewCountsResponse counts() {
    List<Item> all = items(client.list(PENDING));
    OffsetDateTime oldest =
        all.stream()
            .map(Item::receivedAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    return new AdminReviewCountsResponse(all.size(), oldest);
  }

  /** 처리 통계. days는 1~90. */
  public AdminReviewStatsResponse stats(int days) {
    if (days < 1 || days > 90) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "days는 1~90 사이예요");
    }
    JsonNode body = client.stats(days);
    return new AdminReviewStatsResponse(
        days,
        window(body == null ? null : body.path("recent")),
        window(body == null ? null : body.path("today")));
  }

  public AdminReviewDetailResponse detail(String id) {
    JsonNode item = client.detail(id);
    if (item == null) {
      throw new CustomException(ErrorCode.NOTICE_REVIEW_NOT_FOUND);
    }
    JsonNode candidate = item.path("candidate");
    JsonNode raw = item.path("rawEvent").path("payload");
    String title = text(candidate.path("notice").path("title"));
    if (title == null) {
      title = text(raw.path("title"));
    }
    OffsetDateTime received = time(item.path("queuedAt"));
    if (received == null) {
      received = time(item.path("rawEvent").path("occurredAt"));
    }
    String status = text(item.path("reviewStatus"));
    String decidedBy = text(item.path("decidedBy"));
    List<Edit> edits = edits(item.path("edits"));
    List<LogEntry> log = new ArrayList<>();
    if (received != null) {
      log.add(new LogEntry(received, "시스템", "검수 대기열에 추가"));
    }
    OffsetDateTime decided = time(item.path("decidedAt"));
    if (decided != null && status != null && !PENDING.equals(status)) {
      String who = "SUPERSEDED".equals(status) ? "시스템" : decidedBy != null ? decidedBy : "검수자";
      log.add(
          new LogEntry(
              decided,
              who,
              decisionText(status, item.path("edited").asBoolean(false), edits.size())));
    }
    Map<String, Source> evidence = evidence(candidate.path("evidence"));
    List<Field> fields = fields(candidate, evidence);
    Set<String> used = new LinkedHashSet<>();
    fields.forEach(f -> used.addAll(f.sourceIds()));
    List<Source> sources =
        used.stream()
            .map(evidence::get)
            .filter(Objects::nonNull)
            .sorted(
                Comparator.comparing(Source::page, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    return new AdminReviewDetailResponse(
        id,
        reason(text(item.path("jobStatus"))),
        status,
        title,
        text(raw.path("field")),
        text(raw.path("provider")),
        text(raw.path("sourceCode")),
        integer(raw.path("version")),
        text(item.path("rawPostId")),
        received,
        issues(item.path("reasons")),
        fields,
        sources,
        text(item.path("note")),
        decidedBy,
        edits,
        log);
  }

  /**
   * 승인. edits가 있으면 그 칸만 고쳐서 승인해요(검수 API가 계약 형식을 다시 검사해요).
   *
   * @param reviewer 처리 기록에 남길 검수자 (로그인 계정 ID)
   */
  public AdminReviewDecisionResponse approve(
      String id, AdminReviewDecisionRequest request, String reviewer) {
    List<Map<String, Object>> edits = new ArrayList<>();
    if (request != null && request.edits() != null) {
      for (FieldEdit edit : request.edits()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("path", edit.path());
        one.put("value", edit.value());
        edits.add(one);
      }
    }
    client.approve(id, request == null ? null : blankToNull(request.note()), reviewer, edits);
    return new AdminReviewDecisionResponse(id, "APPROVED", remaining());
  }

  /** 반려. 사유가 있으면 메모 앞에 [사유]로 붙여 남겨요(검수 API는 메모 칸 하나만 있어요). */
  public AdminReviewDecisionResponse reject(
      String id, AdminReviewDecisionRequest request, String reviewer) {
    client.reject(id, rejectNote(request), reviewer);
    return new AdminReviewDecisionResponse(id, "REJECTED", remaining());
  }

  static String rejectNote(AdminReviewDecisionRequest request) {
    if (request == null) {
      return null;
    }
    String note = blankToNull(request.note());
    if (request.reason() == null) {
      return note;
    }
    String head = "[" + request.reason().label() + "]";
    return note == null ? head : head + " " + note;
  }

  static String reason(String jobStatus) {
    if ("IN_REVIEW".equals(jobStatus)) {
      return "MUST";
    }
    if ("INTAKE_REVIEW".equals(jobStatus)) {
      return "FORMAT";
    }
    return "CHECK";
  }

  /** 검수 API의 사유는 「코드 /칸」 글자(또는 코드만). 같은 것은 한 번만. */
  static List<Issue> issues(JsonNode reasons) {
    List<Issue> out = new ArrayList<>();
    if (reasons == null || !reasons.isArray()) {
      return out;
    }
    for (JsonNode reason : reasons) {
      Issue issue;
      if (reason.isObject()) {
        issue = new Issue(text(reason.path("code")), text(reason.path("target")));
      } else {
        String value = reason.asString("").trim();
        int space = value.indexOf(' ');
        issue =
            space < 0
                ? new Issue(value, null)
                : new Issue(value.substring(0, space), value.substring(space + 1).trim());
      }
      if (issue.code() != null && !issue.code().isEmpty() && !out.contains(issue)) {
        out.add(issue);
      }
    }
    return out;
  }

  /**
   * 확인할 칸: 보류 칸(HELD)을 먼저, 그다음 검증 이슈가 가리키는 칸(CHECK). 같은 칸의 이유는 합쳐요. 공고 바깥 기록(quality 등)을 가리키는 이슈는
   * 빼요.
   */
  static List<Field> fields(JsonNode candidate, Map<String, Source> evidence) {
    Map<String, FieldBuilder> byPath = new LinkedHashMap<>();
    JsonNode quality = candidate.path("quality");
    for (JsonNode held : quality.path("heldItems")) {
      FieldBuilder field = builder(byPath, candidate, text(held.path("target")));
      if (field != null) {
        field.held = true;
        add(field.reasons, text(held.path("description")));
        held.path("codes").forEach(code -> add(field.codes, code.asString(null)));
      }
    }
    for (JsonNode issue : quality.path("reviewIssues")) {
      FieldBuilder field = builder(byPath, candidate, text(issue.path("target")));
      if (field != null) {
        add(field.reasons, text(issue.path("description")));
        add(field.codes, text(issue.path("code")));
      }
    }
    // 보류된 섹션 안의 칸에 붙은 이슈는 섹션이 보류됐으니 HELD로 보여 줘요
    for (FieldBuilder field : byPath.values()) {
      for (FieldBuilder other : byPath.values()) {
        if (other.held && field.path.startsWith(other.path + "/")) {
          field.held = true;
        }
      }
    }
    return byPath.values().stream()
        .sorted(Comparator.comparing((FieldBuilder f) -> !f.held))
        .limit(MAX_FIELDS)
        .map(f -> f.build(candidate, evidence))
        .toList();
  }

  private static FieldBuilder builder(
      Map<String, FieldBuilder> byPath, JsonNode candidate, String path) {
    if (path == null || !path.startsWith("/")) {
      return null;
    }
    String root = path.substring(1).split("/", 2)[0];
    if (!SECTIONS.containsKey(root) || candidate.at(path).isMissingNode()) {
      return null;
    }
    return byPath.computeIfAbsent(path, FieldBuilder::new);
  }

  private static final class FieldBuilder {
    final String path;
    final List<String> reasons = new ArrayList<>();
    final List<String> codes = new ArrayList<>();
    boolean held;

    FieldBuilder(String path) {
      this.path = path;
    }

    Field build(JsonNode candidate, Map<String, Source> evidence) {
      String[] tokens = path.substring(1).split("/");
      String section = SECTIONS.get(tokens[0]);
      JsonNode node = candidate.at(path);
      JsonNode owner =
          tokens.length >= 2 && tokens[1].matches("\\d+")
              ? candidate.at("/" + tokens[0] + "/" + tokens[1])
              : candidate.path(tokens[0]);
      String label = section;
      String ownerLabel = owner.isObject() ? labelOf(owner) : null;
      if (ownerLabel != null) {
        label = section + " · " + ownerLabel;
      }
      if (tokens.length > 2) {
        label = label + " · " + String.join(".", Arrays.copyOfRange(tokens, 2, tokens.length));
      }
      boolean scalar = node.isValueNode() || node.isNull();
      List<String> sourceIds = new ArrayList<>();
      for (JsonNode id : owner.path("evidenceIds")) {
        String value = id.asString(null);
        if (value != null && evidence.containsKey(value)) {
          sourceIds.add(value);
        }
      }
      return new Field(
          path,
          section,
          label,
          held ? "HELD" : "CHECK",
          scalar ? node : null,
          display(node),
          scalar,
          List.copyOf(reasons),
          List.copyOf(codes),
          sourceIds);
    }
  }

  private static String labelOf(JsonNode item) {
    for (String key : LABEL_KEYS) {
      String value = text(item.path(key));
      if (value != null && !value.isBlank()) {
        return shorten(value, 60);
      }
    }
    return null;
  }

  private static String display(JsonNode node) {
    if (node.isNull()) {
      return "(비어 있음)";
    }
    if (node.isValueNode()) {
      return shorten(node.asString(), 200);
    }
    if (node.isArray()) {
      return node.size() + "개";
    }
    String label = labelOf(node);
    return label != null ? label : "";
  }

  private static Map<String, Source> evidence(JsonNode list) {
    Map<String, Source> out = new LinkedHashMap<>();
    for (JsonNode ev : list) {
      String id = text(ev.path("id"));
      if (id != null) {
        out.put(
            id,
            new Source(
                id,
                integer(ev.path("locator").path("page")),
                shorten(text(ev.path("quote")), 500)));
      }
    }
    return out;
  }

  private static List<Edit> edits(JsonNode list) {
    List<Edit> out = new ArrayList<>();
    for (JsonNode edit : list) {
      out.add(new Edit(text(edit.path("path")), edit.path("before"), edit.path("after")));
    }
    return out;
  }

  private static Window window(JsonNode node) {
    if (node == null || node.isMissingNode()) {
      return new Window(0, 0, 0, null, null, null, null);
    }
    int approved = node.path("approved").asInt(0);
    int edited = node.path("editedApproved").asInt(0);
    int rejected = node.path("rejected").asInt(0);
    int total = approved + edited + rejected;
    return new Window(
        approved,
        edited,
        rejected,
        rate(approved, total),
        rate(edited, total),
        rate(rejected, total),
        integer(node.path("medianMinutes")));
  }

  private static Integer rate(int part, int total) {
    return total == 0 ? null : Math.round(part * 100f / total);
  }

  private static void add(List<String> list, String value) {
    if (value != null && !value.isBlank() && !list.contains(value)) {
      list.add(value);
    }
  }

  private int remaining() {
    JsonNode list = client.list(PENDING);
    return list == null ? 0 : list.size();
  }

  private static List<Item> items(JsonNode list) {
    List<Item> out = new ArrayList<>();
    if (list == null || !list.isArray()) {
      return out;
    }
    for (JsonNode row : list) {
      String title = text(row.path("title"));
      out.add(
          new Item(
              text(row.path("jobId")),
              reason(text(row.path("jobStatus"))),
              title != null ? title : text(row.path("sourceTitle")),
              text(row.path("sourceCode")),
              integer(row.path("rawVersion")),
              time(row.path("queuedAt")),
              issues(row.path("reasons")).size(),
              date(row.path("applyEndsOn"))));
    }
    return out;
  }

  private static String decisionText(String status, boolean edited, int editCount) {
    return switch (status) {
      case "APPROVED" -> !edited ? "승인" : editCount > 0 ? editCount + "칸 고쳐서 승인" : "고친 결과로 승인";
      case "REJECTED" -> "반려";
      case "SUPERSEDED" -> "같은 공고의 새 버전이 발행되어 대체됨";
      default -> status;
    };
  }

  private static String shorten(String value, int max) {
    if (value == null) {
      return null;
    }
    String one = value.replaceAll("\\s+", " ").trim();
    return one.length() <= max ? one : one.substring(0, max) + "…";
  }

  private static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
  }

  private static LocalDate date(JsonNode node) {
    String value = text(node);
    if (value == null || value.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(value.substring(0, 10));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Integer integer(JsonNode node) {
    return node == null || !node.isNumber() ? null : node.asInt();
  }

  /** 검수 API 시각: ISO(2026-10-25T02:33:00+00:00) 또는 파이썬 str(2026-10-25 02:33:00.1+00:00). */
  static OffsetDateTime time(JsonNode node) {
    String value = text(node);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim().replaceFirst(" ", "T"));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
