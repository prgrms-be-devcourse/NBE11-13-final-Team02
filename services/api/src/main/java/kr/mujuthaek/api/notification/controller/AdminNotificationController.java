package kr.mujuthaek.api.notification.controller;

import kr.mujuthaek.api.notification.dto.response.AdminNotificationStatsResponse;
import kr.mujuthaek.api.notification.service.internal.NotificationStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 「알림」 화면 (트래픽 보기 권한, SecurityConfig). */
@RestController
class AdminNotificationController {

  private final NotificationStatsService stats;

  AdminNotificationController(NotificationStatsService stats) {
    this.stats = stats;
  }

  /**
   * 최근 {@code days}일 발송 결과와 봤는지·눌렀는지.
   *
   * @param days 기본 7, 1~90
   */
  @GetMapping("/api/admin/notifications/stats")
  AdminNotificationStatsResponse stats(@RequestParam(defaultValue = "7") int days) {
    return stats.stats(days);
  }
}
