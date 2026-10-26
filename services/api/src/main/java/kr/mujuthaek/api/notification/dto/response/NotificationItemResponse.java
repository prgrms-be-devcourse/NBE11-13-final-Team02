package kr.mujuthaek.api.notification.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * 알림함 한 줄.
 *
 * @param type NEW_NOTICE · NEW_SUPPORT · DEADLINE_D3 · DEADLINE_D1 · CORRECTED · FOLLOW_UP
 * @param linkPath 누르면 갈 PWA 화면 (/notices/{id} …)
 * @param readAt 읽은 시각. 안 읽었으면 null
 */
public record NotificationItemResponse(
    UUID id,
    String type,
    Target target,
    String title,
    String body,
    String linkPath,
    Instant createdAt,
    Instant readAt) {

  /**
   * @param type NOTICE · BENEFIT · SPACE_RECRUITMENT
   */
  public record Target(String type, UUID id) {}
}
