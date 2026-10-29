package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.response.AdminBenefitReviewCountsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminBenefitReviewDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminBenefitReviewListResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDecisionResponse;
import kr.mujuthaek.api.notice.service.internal.AdminBenefitReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 혜택(주거·이주 정책) 검수 화면 API (ADMIN만, /api/admin/**). 추출 서비스 혜택 검수 API를 이 서버가 대신 불러요.
 *
 * <pre>
 * GET  /api/admin/benefit-reviews?status=PENDING   목록 (PENDING·APPROVED·REJECTED·SUPERSEDED)
 * GET  /api/admin/benefit-reviews/counts           대기 건수 (사이드바·개요 숫자. 목록 전체를 받지 않아요)
 * GET  /api/admin/benefit-reviews/{id}             상세: 정제 결과·받은 원문·처리 기록
 * POST /api/admin/benefit-reviews/{id}/approve     승인 { "note": "…", "edits": [{ "path": "/conditions/0/value", "value": … }] }
 * POST /api/admin/benefit-reviews/{id}/reject      반려(서비스에 안 보이게) { "reason": "OUT_OF_SCOPE", "note": "…" }
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/benefit-reviews")
class AdminBenefitReviewController {

  private final AdminBenefitReviewService reviews;

  AdminBenefitReviewController(AdminBenefitReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  AdminBenefitReviewListResponse list(@RequestParam(required = false) String status) {
    return reviews.list(status);
  }

  @GetMapping("/counts")
  AdminBenefitReviewCountsResponse counts() {
    return reviews.counts();
  }

  @GetMapping("/{id}")
  AdminBenefitReviewDetailResponse detail(@PathVariable UUID id) {
    return reviews.detail(id.toString());
  }

  @PostMapping("/{id}/approve")
  AdminReviewDecisionResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AdminReviewDecisionRequest request,
      Principal principal) {
    return reviews.approve(id.toString(), request, reviewer(principal));
  }

  @PostMapping("/{id}/reject")
  AdminReviewDecisionResponse reject(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AdminReviewDecisionRequest request,
      Principal principal) {
    return reviews.reject(id.toString(), request, reviewer(principal));
  }

  private static String reviewer(Principal principal) {
    return principal == null ? null : principal.getName();
  }
}
