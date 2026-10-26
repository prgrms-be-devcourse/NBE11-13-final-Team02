package kr.mujuthaek.api.notice.controller;

import kr.mujuthaek.api.notice.dto.response.AdminActivityResponse;
import kr.mujuthaek.api.notice.service.internal.AdminActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 「처리 기록」 (ADMIN만, SecurityConfig의 /api/admin/**).
 *
 * <pre>
 * GET /api/admin/activity?days=30   검수·제보 처리를 최근 순으로 (days 1~90, 최대 300줄)
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/activity")
class AdminActivityController {

  private final AdminActivityService activity;

  AdminActivityController(AdminActivityService activity) {
    this.activity = activity;
  }

  @GetMapping
  AdminActivityResponse activity(@RequestParam(defaultValue = "30") int days) {
    return activity.activity(days);
  }
}
