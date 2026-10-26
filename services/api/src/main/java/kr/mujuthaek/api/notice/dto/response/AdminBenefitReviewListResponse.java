package kr.mujuthaek.api.notice.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 혜택(주거·이주 정책) 검수 목록.
 *
 * @param items 오래 기다린 것부터
 */
public record AdminBenefitReviewListResponse(List<Item> items) {

  /**
   * 검수 한 건.
   *
   * @param id 검수 ID
   * @param rawPolicyId 정책 원본 ID (같은 정책은 버전이 바뀌어도 같아요)
   * @param sourceCode 출처 (youth 온통청년 등)
   * @param status PENDING · APPROVED · REJECTED · SUPERSEDED
   * @param reasons 확인할 이유: EVIDENCE_NOT_FOUND 근거를 원문에서 못 찾음 · LOW_CONFIDENCE 기본 기준·전형별 값 등으로 채움 ·
   *     RULE_AI_CONFLICT 출처 칸과 원문 값이 다름 · TEXT_TRUNCATED 원문이 잘림 · NO_DOCUMENT 첨부 공고문 없음 · REPORTED
   *     정책 제보로 다시 엶
   * @param reported 정책 제보로 다시 연 건인지
   * @param relevant 주거·이주 혜택으로 판정했는지 (false면 서비스에 안 보여요)
   * @param applyEndsOn 신청 마감일 (모르면 null)
   */
  public record Item(
      String id,
      String rawPolicyId,
      int rawVersion,
      String sourceCode,
      String policyId,
      String title,
      String status,
      List<String> reasons,
      boolean reported,
      boolean relevant,
      LocalDate applyEndsOn,
      OffsetDateTime queuedAt,
      OffsetDateTime decidedAt) {}
}
