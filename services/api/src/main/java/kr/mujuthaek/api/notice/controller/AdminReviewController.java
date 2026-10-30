package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.response.AdminReviewCountsResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDecisionResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewListResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewQueueResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReviewStatsResponse;
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
 * GET  /api/admin/reviews/queue?kinds=NOTICE,BENEFIT&amp;reason=&amp;cursor=&amp;size=50  대기열 한 페이지: 공고·혜택 검수를 들어온 시각 순으로 (다음은 nextCursor, kinds마다 그 검수 권한)
 * GET  /api/admin/reviews/counts                     대기 건수 · 가장 오래된 시각
 * GET  /api/admin/reviews/stats?days=7               처리 통계 (최근 days일 · 오늘)
 * GET  /api/admin/reviews/urgent?days=3              마감이 가까운 검수 대기
 * GET  /api/admin/reviews/{id}                       상세: 확인할 칸·이유·원문 근거·처리 기록
 * POST /api/admin/reviews/{id}/approve               승인 { "note": "…", "edits": [{ "path": "…", "value": … }] }
 * POST /api/admin/reviews/{id}/reject                반려 { "reason": "DUPLICATE", "note": "…" }
 * </pre>
 *
 * <p>처리 기록의 검수자는 로그인 계정 ID(Principal 이름)예요. 사용자 모듈 타입에 기대지 않으려고 표준 Principal로 받아요.
 */
@RestController
@RequestMapping("/api/admin/reviews")
class AdminReviewController {

  private final AdminReviewService reviews;

  AdminReviewController(AdminReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping("/queue")
  AdminReviewQueueResponse queue(
      @RequestParam(required = false) String kinds,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int size) {
    return reviews.queue(kinds, reason, cursor, size);
  }

  @GetMapping("/counts")
  AdminReviewCountsResponse counts() {
    return reviews.counts();
  }

  @GetMapping("/stats")
  AdminReviewStatsResponse stats(@RequestParam(defaultValue = "7") int days) {
    return reviews.stats(days);
  }

  @GetMapping("/urgent")
  List<AdminReviewListResponse.Item> urgent(@RequestParam(defaultValue = "3") int days) {
    return reviews.urgent(days);
  }

  @GetMapping("/{id}")
  AdminReviewDetailResponse detail(@PathVariable UUID id) {
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
