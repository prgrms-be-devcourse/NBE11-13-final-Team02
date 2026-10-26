package kr.mujuthaek.api.notification.controller;

import java.util.UUID;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.notification.dto.response.NotificationItemResponse;
import kr.mujuthaek.api.notification.dto.response.UnreadCountResponse;
import kr.mujuthaek.api.notification.service.internal.InboxService;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 알림함 (로그인한 회원만, {@code /api/users/**}). 알림은 90일 동안 남아요. */
@RestController
@RequestMapping("/api/users/me/notifications")
class NotificationInboxController {

  private final InboxService inbox;

  NotificationInboxController(InboxService inbox) {
    this.inbox = inbox;
  }

  /**
   * 내 알림 (최근 것부터).
   *
   * @param size 기본 20, 1~100
   * @param unread true면 안 읽은 것만
   */
  @GetMapping
  CursorPageResponse<NotificationItemResponse> list(
      @AuthenticationPrincipal LoginUser user,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size,
      @RequestParam(defaultValue = "false") boolean unread) {
    return inbox.list(user.userId(), cursor, size, unread);
  }

  @GetMapping("/unread-count")
  UnreadCountResponse unreadCount(@AuthenticationPrincipal LoginUser user) {
    return new UnreadCountResponse(inbox.unreadCount(user.userId()));
  }

  /** 읽음. 이미 읽었어도 204, 내 알림이 아니면 404 */
  @PostMapping("/{id}/read")
  ResponseEntity<Void> markRead(@AuthenticationPrincipal LoginUser user, @PathVariable UUID id) {
    inbox.markRead(user.userId(), id);
    return ResponseEntity.noContent().build();
  }

  /** 모두 읽음 */
  @PostMapping("/read-all")
  ResponseEntity<Void> markAllRead(@AuthenticationPrincipal LoginUser user) {
    inbox.markAllRead(user.userId());
    return ResponseEntity.noContent().build();
  }
}
