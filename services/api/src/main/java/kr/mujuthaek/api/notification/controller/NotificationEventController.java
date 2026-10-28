package kr.mujuthaek.api.notification.controller;

import java.util.UUID;
import kr.mujuthaek.api.notification.service.internal.InboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림을 봤는지·눌렀는지 알려 받기. 웹 푸시를 받은 서비스 워커는 로그인 토큰이 없어서 알림함 줄 ID만으로 받아요 (로그인 없이). 처음 한 번만 적고, 줄이 있는지는 알려
 * 주지 않아요 (늘 204).
 */
@RestController
class NotificationEventController {

  private final InboxService inbox;

  NotificationEventController(InboxService inbox) {
    this.inbox = inbox;
  }

  /** 웹 푸시가 기기에 떴어요 */
  @PostMapping("/api/v1/notifications/{id}/shown")
  ResponseEntity<Void> shown(@PathVariable UUID id) {
    inbox.markShown(id);
    return ResponseEntity.noContent().build();
  }

  /** 푸시나 알림 목록에서 눌러 그 화면으로 갔어요 (읽음도 함께) */
  @PostMapping("/api/v1/notifications/{id}/clicked")
  ResponseEntity<Void> clicked(@PathVariable UUID id) {
    inbox.markClicked(id);
    return ResponseEntity.noContent().build();
  }
}
