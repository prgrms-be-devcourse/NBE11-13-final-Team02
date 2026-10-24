package kr.mujuthaek.api.notice.service.internal;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notice.client.ExtractionReviewClient;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.response.AdminReviewCountsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDecisionResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Issue;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.LogEntry;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse.Item;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 검수 화면용으로 추출 검수 API 응답을 옮겨요. 이 서버에는 검수 데이터를 저장하지 않아요(추출 서비스 DB가 원본).
 *
 * <p>사유 묶음은 작업 상태로 정해요: IN_REVIEW(아직 발행 안 함) → MUST, PARTIAL(발행했고 일부 칸 보류) → CHECK,
 * INTAKE_REVIEW(원본 자료끼리 맞지 않아 추출 안 함) → FORMAT.
 */
@Service
public class AdminReviewService {

  public static final List<String> REASONS = List.of("MUST", "CHECK", "FORMAT");
  private static final String PENDING = "PENDING";

  private final ExtractionReviewClient client;

  public AdminReviewService(ExtractionReviewClient client) {
    this.client = client;
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
    List<LogEntry> log = new ArrayList<>();
    if (received != null) {
      log.add(new LogEntry(received, "시스템", "검수 대기열에 추가"));
    }
    OffsetDateTime decided = time(item.path("decidedAt"));
    if (decided != null && status != null && !PENDING.equals(status)) {
      log.add(
          new LogEntry(decided, "SUPERSEDED".equals(status) ? "시스템" : "검수자", decisionText(status)));
    }
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
        text(item.path("note")),
        log);
  }

  public AdminReviewDecisionResponse approve(String id, AdminReviewDecisionRequest request) {
    client.approve(id, request == null ? null : blankToNull(request.note()));
    return new AdminReviewDecisionResponse(id, "APPROVED", remaining());
  }

  /** 반려. 사유가 있으면 메모 앞에 [사유]로 붙여 남겨요(검수 API는 메모 칸 하나만 있어요). */
  public AdminReviewDecisionResponse reject(String id, AdminReviewDecisionRequest request) {
    client.reject(id, rejectNote(request));
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
              issues(row.path("reasons")).size()));
    }
    return out;
  }

  private static String decisionText(String status) {
    return switch (status) {
      case "APPROVED" -> "승인";
      case "REJECTED" -> "반려";
      case "SUPERSEDED" -> "같은 공고의 새 버전이 발행되어 대체됨";
      default -> status;
    };
  }

  private static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
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
