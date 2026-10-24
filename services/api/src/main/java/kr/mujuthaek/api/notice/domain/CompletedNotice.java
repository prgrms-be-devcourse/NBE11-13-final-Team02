package kr.mujuthaek.api.notice.domain;

import java.time.LocalDate;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * extraction.completed payload에서 notice가 쓰는 값 (contracts/events/extraction.completed.v1.json).
 *
 * <p>글 종류(kind)와 원래 공고(supersedesPostId)는 상세 notice의 sourceReferences에서 이 원본(rawPostId)을 가리키는 항목으로
 * 봐요. 상세가 없으면(IN_REVIEW에서 빠질 수 있음) 모집공고로 봐요.
 *
 * @param payload 받은 payload 전체 (상세 화면용으로 그대로 저장)
 */
public record CompletedNotice(
    UUID rawPostId,
    int rawVersion,
    String field,
    String kind,
    UUID supersedesPostId,
    String reviewStatus,
    String title,
    String provider,
    String supplyTypeText,
    NoticeType noticeType,
    String regionCode,
    LocalDate applyStart,
    LocalDate applyEnd,
    LocalDate postedOn,
    String summary,
    String detailUrl,
    boolean reprocess,
    JsonNode payload) {

  /** notice가 받는 분야 (정책·창업지원은 benefit이 받아요). */
  public boolean isNoticeField() {
    return "HOUSING".equals(field) || "BUSINESS_SPACE".equals(field);
  }

  /** 공개해도 되는 결과인지 (검수 대기면 이전 공개 버전을 유지해요). */
  public boolean publishable() {
    return !"IN_REVIEW".equals(reviewStatus);
  }

  public boolean approved() {
    return "APPROVED".equals(reviewStatus);
  }

  public static CompletedNotice from(JsonNode p) {
    UUID rawPostId = UUID.fromString(p.get("rawPostId").asString());
    String field = p.get("field").asString();
    JsonNode detail = p.path("notice");
    JsonNode source = sourceReference(detail, rawPostId);
    String supplyType = text(detail.path("notice").path("supplyType"));
    LocalDate announced = date(detail.path("notice").path("announcedOn"));
    return new CompletedNotice(
        rawPostId,
        p.get("rawVersion").asInt(),
        field,
        source.path("kind").isMissingNode() ? "NOTICE" : source.path("kind").asString(),
        uuid(source.path("supersedesPostId")),
        p.get("reviewStatus").asString(),
        p.get("title").asString(),
        text(p.path("provider")),
        supplyType,
        NoticeType.fromSupplyType(supplyType, field),
        text(p.path("regionCode")),
        date(p.path("schedule").path("applyStartOn")),
        date(p.path("schedule").path("applyEndOn")),
        announced != null ? announced : date(source.path("postedOn")),
        text(p.path("summary")),
        text(source.path("detailUrl")),
        p.path("reprocess").asBoolean(false),
        p);
  }

  private static JsonNode sourceReference(JsonNode detail, UUID rawPostId) {
    for (JsonNode ref : detail.path("sourceReferences")) {
      if (rawPostId.toString().equals(ref.path("rawPostId").asString(null))) {
        return ref;
      }
    }
    return detail.path("sourceReferences").path(0);
  }

  private static String text(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asString();
  }

  private static LocalDate date(JsonNode node) {
    String value = text(node);
    return value == null ? null : LocalDate.parse(value);
  }

  private static UUID uuid(JsonNode node) {
    String value = text(node);
    return value == null ? null : UUID.fromString(value);
  }
}
