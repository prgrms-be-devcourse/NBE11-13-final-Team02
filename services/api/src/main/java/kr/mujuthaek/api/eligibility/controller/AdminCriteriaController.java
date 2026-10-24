package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.request.AdminCriteriaRequest;
import kr.mujuthaek.api.eligibility.dto.request.RegulatedAreaRequest;
import kr.mujuthaek.api.eligibility.dto.response.AdminCriteriaResponse;
import kr.mujuthaek.api.eligibility.dto.response.RegulatedAreaResponse;
import kr.mujuthaek.api.eligibility.service.AdminCriteriaService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 기준값 화면 API (ADMIN만, /api/admin/**).
 *
 * <pre>
 * GET  /api/admin/criteria/{kind}                     연도별 값 (오늘 쓰는 값·예약·지난 해)
 * PUT  /api/admin/criteria/{kind}/{year}              한 해 값 넣기·바꾸기 { "validFrom": "2027-01-01", "data": {…}, "source": "…" }
 * GET  /api/admin/criteria/{kind}/history             바꾼 기록
 * GET  /api/admin/regulated-areas?active=true         규제지역
 * POST /api/admin/regulated-areas                     지정 { "regionCode": "11680", "kind": "SPECULATION_OVERHEATED", "validFrom": … }
 * POST /api/admin/regulated-areas/{id}/end?until=…    해제
 * </pre>
 *
 * <p>고친 관리자는 로그인 계정 ID(Principal 이름)로 남겨요.
 */
@RestController
@RequestMapping("/api/admin")
class AdminCriteriaController {

  private final AdminCriteriaService criteria;

  AdminCriteriaController(AdminCriteriaService criteria) {
    this.criteria = criteria;
  }

  @GetMapping("/criteria/{kind}")
  AdminCriteriaResponse criteria(@PathVariable String kind) {
    return criteria.criteria(kind);
  }

  @PutMapping("/criteria/{kind}/{year}")
  AdminCriteriaResponse save(
      @PathVariable String kind,
      @PathVariable int year,
      @Valid @RequestBody AdminCriteriaRequest request,
      Principal principal) {
    return criteria.save(kind, year, request, admin(principal));
  }

  @GetMapping("/criteria/{kind}/history")
  AdminCriteriaResponse.Changes history(@PathVariable String kind) {
    return criteria.history(kind);
  }

  @GetMapping("/regulated-areas")
  List<RegulatedAreaResponse> regulatedAreas(@RequestParam(defaultValue = "false") boolean active) {
    return criteria.regulatedAreas(active);
  }

  @PostMapping("/regulated-areas")
  RegulatedAreaResponse add(@Valid @RequestBody RegulatedAreaRequest request, Principal principal) {
    return criteria.addRegulatedArea(request, admin(principal));
  }

  @PostMapping("/regulated-areas/{id}/end")
  RegulatedAreaResponse end(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate until,
      Principal principal) {
    return criteria.endRegulatedArea(id, until, admin(principal));
  }

  private static String admin(Principal principal) {
    return principal == null ? null : principal.getName();
  }
}
