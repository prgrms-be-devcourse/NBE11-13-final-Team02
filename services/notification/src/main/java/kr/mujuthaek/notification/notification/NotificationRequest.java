package kr.mujuthaek.notification.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * notification.requested의 payload (contracts/schemas/notification-events.draft.md). 받을 회원(userIds)은
 * api가 이미 골라서 보내요. 알림 서버는 이 목록을 다시 따지지 않아요.
 */
public record NotificationRequest(
    NotificationType type,
    String dedupKey,
    List<UUID> userIds,
    Target target,
    Correction correction,
    FollowUp followUp) {

  static final int MAX_USERS = 500;

  public record Target(
      TargetType type,
      UUID id,
      String title,
      String provider,
      LocalDate applyEnd,
      String linkPath) {}

  public record Correction(List<String> changedFields) {}

  public record FollowUp(String title, LocalDate postedOn) {}

  /** 계약에 맞지 않으면 IllegalArgumentException. 다시 받아도 같아서 실패 큐로 보내요. */
  public void validate() {
    require(type != null, "type이 없어요");
    require(dedupKey != null && !dedupKey.isBlank(), "dedupKey가 없어요");
    require(userIds != null && !userIds.isEmpty(), "userIds가 비었어요");
    require(userIds.size() <= MAX_USERS, "userIds는 " + MAX_USERS + "명까지예요");
    require(target != null && target.type() != null && target.id() != null, "target이 없어요");
    require(target.title() != null && !target.title().isBlank(), "target.title이 없어요");
    require(
        target.linkPath() != null && target.linkPath().startsWith("/"), "target.linkPath가 이상해요");
    require(
        type != NotificationType.CORRECTED || correction != null, "CORRECTED는 correction이 있어야 해요");
    require(type != NotificationType.FOLLOW_UP || followUp != null, "FOLLOW_UP은 followUp이 있어야 해요");
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}
