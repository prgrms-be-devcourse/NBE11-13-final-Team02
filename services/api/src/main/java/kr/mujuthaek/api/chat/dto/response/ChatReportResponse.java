package kr.mujuthaek.api.chat.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;
import kr.mujuthaek.api.chat.domain.MessageStatus;
import kr.mujuthaek.api.chat.domain.ReportReason;
import kr.mujuthaek.api.chat.domain.ReviewStatus;

/**
 * 관리자 신고 목록의 신고 하나. 신고한 회원은 보여주지 않아요.
 *
 * @param noticeId 메시지가 있는 채팅방 (공고 ID)
 * @param message 신고된 메시지. 가려지거나 작성자가 지운 메시지도 검토하려고 본문을 보여줘요 (탈퇴한 회원의 메시지는 빈 글자)
 */
public record ChatReportResponse(
    UUID reportId,
    UUID messageId,
    UUID noticeId,
    ReportReason reason,
    String detail,
    ReviewStatus reviewStatus,
    OffsetDateTime reportedAt,
    Message message) {

  /**
   * @param reportCount 검토 전 신고 수
   */
  public record Message(
      String authorNickname, String content, MessageStatus status, int reportCount) {}
}
