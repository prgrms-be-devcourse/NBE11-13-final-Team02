package kr.mujuthaek.api.notice.controller;

import kr.mujuthaek.api.notice.client.CollectorAdminClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 수집 현황 화면 API (ADMIN만). 수집기 관리자 API를 이 서버가 대신 불러요.
 *
 * <pre>
 * GET  /api/admin/sources               출처별 상태 (정상·지연·실패·멈춤, 최근 회차, 연속 0건)
 * POST /api/admin/sources/{code}/pause  멈춤
 * POST /api/admin/sources/{code}/resume 다시 켜기
 * POST /api/admin/sources/{code}/run    지금 한 번 수집 (뒤에서 돌아요, 다른 수집 중이면 409)
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/sources")
class AdminSourceController {

  private final CollectorAdminClient collector;

  AdminSourceController(CollectorAdminClient collector) {
    this.collector = collector;
  }

  @GetMapping
  JsonNode sources() {
    return collector.sources();
  }

  @PostMapping("/{code}/pause")
  JsonNode pause(@PathVariable String code) {
    return collector.setEnabled(code, false);
  }

  @PostMapping("/{code}/run")
  JsonNode run(@PathVariable String code) {
    return collector.runNow(code);
  }

  @PostMapping("/{code}/resume")
  JsonNode resume(@PathVariable String code) {
    return collector.setEnabled(code, true);
  }
}
