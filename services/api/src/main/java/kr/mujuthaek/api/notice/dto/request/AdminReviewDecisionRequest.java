package kr.mujuthaek.api.notice.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 승인·반려 본문 (둘 다 비워도 돼요).
 *
 * @param reason 반려 사유: DUPLICATE 중복 공고 · SOURCE_MISMATCH 원문과 다름(다시 추출) · OUT_OF_SCOPE 서비스 대상 아님 ·
 *     CLOSED 마감된 공고. 승인에서는 무시해요
 * @param note 검수자 메모
 */
public record AdminReviewDecisionRequest(RejectReason reason, @Size(max = 500) String note) {

  /** 프론트 반려 사유 선택지와 같은 순서예요. */
  public enum RejectReason {
    DUPLICATE("중복 공고"),
    SOURCE_MISMATCH("원문과 다름 · 다시 추출"),
    OUT_OF_SCOPE("서비스 대상 아님"),
    CLOSED("마감된 공고");

    private final String label;

    RejectReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }
}
