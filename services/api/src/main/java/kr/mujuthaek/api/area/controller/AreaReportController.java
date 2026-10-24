package kr.mujuthaek.api.area.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import kr.mujuthaek.api.area.dto.request.AreaReportRequest;
import kr.mujuthaek.api.area.dto.request.AreaReportResolveRequest;
import kr.mujuthaek.api.area.dto.response.AreaReportResponse;
import kr.mujuthaek.api.area.service.AreaReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 생활권 제보 API.
 *
 * <pre>
 * POST /api/v1/notices/{id}/area-reports       회원 제보 (로그인 필요)
 * GET  /api/admin/area-reports?status=…        관리자 목록 · 건수 (ADMIN)
 * GET  /api/admin/area-reports/counts
 * GET  /api/admin/area-reports/{id}
 * POST /api/admin/area-reports/{id}/start
 * POST /api/admin/area-reports/{id}/resolve    { "result": "FIXED", "reply": "…", "group": true }
 * </pre>
 *
 * <p>회원·관리자는 로그인 계정 ID(Principal 이름)로만 남겨요.
 */
@RestController
class AreaReportController {

  private final AreaReportService reports;

  AreaReportController(AreaReportService reports) {
    this.reports = reports;
  }

  @PostMapping("/api/v1/notices/{id}/area-reports")
  AreaReportResponse.Created report(
      @PathVariable UUID id, @Valid @RequestBody AreaReportRequest request, Principal principal) {
    return reports.report(id, request, name(principal));
  }

  @GetMapping("/api/admin/area-reports")
  AreaReportResponse.ListView list(@RequestParam(required = false) String status) {
    return reports.list(status);
  }

  @GetMapping("/api/admin/area-reports/counts")
  AreaReportResponse.Counts counts() {
    return reports.counts();
  }

  @GetMapping("/api/admin/area-reports/{id}")
  AreaReportResponse.Detail detail(@PathVariable UUID id) {
    return reports.detail(id);
  }

  @PostMapping("/api/admin/area-reports/{id}/start")
  AreaReportResponse.Detail start(@PathVariable UUID id, Principal principal) {
    return reports.start(id, name(principal));
  }

  @PostMapping("/api/admin/area-reports/{id}/resolve")
  AreaReportResponse.Detail resolve(
      @PathVariable UUID id,
      @Valid @RequestBody AreaReportResolveRequest request,
      Principal principal) {
    return reports.resolve(id, request, name(principal));
  }

  private static String name(Principal principal) {
    return principal == null ? null : principal.getName();
  }
}
