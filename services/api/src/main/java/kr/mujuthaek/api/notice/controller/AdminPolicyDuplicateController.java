package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.response.AdminPolicyDuplicateResponse;
import kr.mujuthaek.api.notice.service.internal.PolicyDuplicateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 「제보·신고」의 「중복 의심」 (제보 처리 권한). 출처에 두 번 올라온 것 같은 창업 지원·입주 모집 사업.
 *
 * <pre>
 * GET  /api/admin/policy-duplicates?status=PENDING|KEPT_BOTH|HIDDEN   목록 · 상태별 건수
 * GET  /api/admin/policy-duplicates/counts                           상태별 건수 (사이드바)
 * POST /api/admin/policy-duplicates/{laterId}/{earlierId}/decide      { "action": "KEEP_BOTH" | "HIDE_LATER" }
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/policy-duplicates")
class AdminPolicyDuplicateController {

  private final PolicyDuplicateService duplicates;

  AdminPolicyDuplicateController(PolicyDuplicateService duplicates) {
    this.duplicates = duplicates;
  }

  @GetMapping
  AdminPolicyDuplicateResponse list(@RequestParam(required = false) String status) {
    return duplicates.list(status);
  }

  @GetMapping("/counts")
  AdminPolicyDuplicateResponse.Counts counts() {
    return duplicates.counts();
  }

  /**
   * @param action KEEP_BOTH(둘 다 둠) · HIDE_LATER(나중 것 숨김)
   */
  record DecideRequest(@NotBlank String action) {}

  @PostMapping("/{later}/{earlier}/decide")
  ResponseEntity<Void> decide(
      @PathVariable UUID later,
      @PathVariable UUID earlier,
      @Valid @RequestBody DecideRequest request,
      Principal principal) {
    duplicates.decide(
        later, earlier, request.action(), principal == null ? null : principal.getName());
    return ResponseEntity.noContent().build();
  }
}
