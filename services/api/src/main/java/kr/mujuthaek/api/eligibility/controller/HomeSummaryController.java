package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import kr.mujuthaek.api.eligibility.dto.request.HomeSummaryRequest;
import kr.mujuthaek.api.eligibility.dto.response.HomeSummaryResponse;
import kr.mujuthaek.api.eligibility.service.HomeSummaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈 요약 POST /api/v1/home/summary. 판정이 섞인 요약이라 맞춤 목록처럼 eligibility에 있어요. 판정 입력은 저장하지 않고 응답도 캐시하지
 * 않아요.
 */
@RestController
class HomeSummaryController {

  private final HomeSummaryService summaries;

  HomeSummaryController(HomeSummaryService summaries) {
    this.summaries = summaries;
  }

  @PostMapping("/api/v1/home/summary")
  ResponseEntity<HomeSummaryResponse> summary(@Valid @RequestBody HomeSummaryRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(summaries.summary(request));
  }
}
