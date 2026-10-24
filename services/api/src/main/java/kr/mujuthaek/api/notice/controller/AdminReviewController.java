package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.response.AdminReviewCountsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDecisionResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse;
import kr.mujuthaek.api.notice.service.internal.AdminReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 검수 화면 API (ADMIN만, SecurityConfig의 /api/admin/**). 추출 서비스 검수 API를 이 서버가 대신 불러요.
 *
 * <pre>
 * GET  /api/admin/reviews?reason=MUST|CHECK|FORMAT   대기 목록
 * GET  /api/admin/reviews/counts                     대기 건수 · 가장 오래된 시각
 * GET  /api/admin/reviews/{id}                       상세 머리 · 이슈 · 처리 기록
 * POST /api/admin/reviews/{id}/approve               승인 { "note": "…" }
 * POST /api/admin/reviews/{id}/reject                반려 { "reason": "DUPLICATE", "note": "…" }
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/reviews")
class AdminReviewController {

  private final AdminReviewService reviews;

  AdminReviewController(AdminReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  AdminReviewListResponse list(@RequestParam(required = false) String reason) {
    return reviews.pending(reason);
  }

  @GetMapping("/counts")
  AdminReviewCountsResponse counts() {
    return reviews.counts();
  }

  @GetMapping("/{id}")
  AdminReviewDetailResponse detail(@PathVariable UUID id) {
    return reviews.detail(id.toString());
  }

  @PostMapping("/{id}/approve")
  AdminReviewDecisionResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AdminReviewDecisionRequest request) {
    return reviews.approve(id.toString(), request);
  }

  @PostMapping("/{id}/reject")
  AdminReviewDecisionResponse reject(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AdminReviewDecisionRequest request) {
    return reviews.reject(id.toString(), request);
  }
}
