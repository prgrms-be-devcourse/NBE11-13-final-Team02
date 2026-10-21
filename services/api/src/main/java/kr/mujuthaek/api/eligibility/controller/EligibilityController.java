package kr.mujuthaek.api.eligibility.controller;

import java.time.Duration;
import kr.mujuthaek.api.eligibility.dto.response.CriteriaResponse;
import kr.mujuthaek.api.eligibility.service.CriteriaService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 판정 API는 /eligibility 아래에 모아요 (판정 경로 B). 로그인 없이 써요. */
@RestController
@RequestMapping("/api/v1/eligibility")
class EligibilityController {

  private final CriteriaService criteria;

  EligibilityController(CriteriaService criteria) {
    this.criteria = criteria;
  }

  /** 판정 기준 금액 (연도별 기준 중위소득, 순위 요건). 하루 캐시. */
  @GetMapping("/criteria")
  ResponseEntity<CriteriaResponse> criteria(
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) String noticeType) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
        .body(criteria.criteria(year, noticeType));
  }
}
