package kr.mujuthaek.api.notice.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 검수 상세의 머리·이슈·처리 기록. 원문 강조와 칸별 값은 아직 없어요(다음 단계).
 *
 * @param reason MUST · CHECK · FORMAT ({@link AdminReviewListResponse.Item#reason()}와 같아요)
 * @param reviewStatus PENDING · APPROVED · REJECTED · SUPERSEDED
 * @param field 공고 분야 (HOUSING 등)
 * @param provider 공급 기관 (예: LH)
 * @param issues 이슈 코드와 가리키는 칸 (예: VALUE_NOT_IN_QUOTE, /eligibility/3/value)
 */
public record AdminReviewDetailResponse(
    String id,
    String reason,
    String reviewStatus,
    String title,
    String field,
    String provider,
    String sourceCode,
    Integer rawVersion,
    String rawPostId,
    OffsetDateTime receivedAt,
    List<Issue> issues,
    String note,
    List<LogEntry> log) {

  /** target은 이슈가 가리키는 칸(JSON Pointer). 공고 전체에 대한 이슈면 null. */
  public record Issue(String code, String target) {}

  /** 처리 기록 한 줄. who: 시스템 · 검수자. */
  public record LogEntry(OffsetDateTime at, String who, String text) {}
}
