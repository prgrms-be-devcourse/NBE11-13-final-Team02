package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.request.AdminReportResolveRequest;
import kr.mujuthaek.api.notice.dto.response.AdminReportDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReportListResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReportReviewResponse;
import kr.mujuthaek.api.notice.service.internal.NoticeReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 제보 화면 API (ADMIN만). 공고 제보만 다뤄요 — 혜택·생활권 제보는 각 담당 모듈이에요.
 *
 * <pre>
 * GET  /api/admin/reports?status=NEW|DOING|DONE   목록 · 상태별 건수
 * GET  /api/admin/reports/counts                  상태별 건수 (사이드바)
 * GET  /api/admin/reports/{id}                    상세 · 같은 칸의 다른 제보
 * POST /api/admin/reports/{id}/start              처리 시작
 * POST /api/admin/reports/{id}/review             고치기: 공고 결과를 검수로 다시 열기 (검수에서 칸을 고쳐 승인)
 * POST /api/admin/reports/{id}/resolve            처리 끝 { "result": "FIXED", "reply": "…", "group": true }
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/reports")
class AdminReportController {

  private final NoticeReportService reports;

  AdminReportController(NoticeReportService reports) {
    this.reports = reports;
  }

  @GetMapping
  AdminReportListResponse list(@RequestParam(required = false) String status) {
    return reports.list(status);
  }

  @GetMapping("/counts")
  AdminReportListResponse.Counts counts() {
    return reports.counts();
  }

  @GetMapping("/{id}")
  AdminReportDetailResponse detail(@PathVariable UUID id) {
    return reports.detail(id);
  }

  @PostMapping("/{id}/start")
  AdminReportDetailResponse start(@PathVariable UUID id, Principal principal) {
    return reports.start(id, admin(principal));
  }

  @PostMapping("/{id}/review")
  AdminReportReviewResponse review(@PathVariable UUID id, Principal principal) {
    return reports.openReview(id, admin(principal));
  }

  @PostMapping("/{id}/resolve")
  AdminReportDetailResponse resolve(
      @PathVariable UUID id,
      @Valid @RequestBody AdminReportResolveRequest request,
      Principal principal) {
    return reports.resolve(id, request, admin(principal));
  }

  private static String admin(Principal principal) {
    return principal == null ? null : principal.getName();
  }
}
