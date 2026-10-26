package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.request.SpaceEvaluateRequest;
import kr.mujuthaek.api.eligibility.dto.request.SupportSearchRequest;
import kr.mujuthaek.api.eligibility.dto.response.SupportEvaluationResponse;
import kr.mujuthaek.api.eligibility.dto.response.SupportSearchResponse;
import kr.mujuthaek.api.eligibility.service.SupportSearchService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사업 지원 맞춤 목록 POST /api/v1/supports/search 와 사업 하나의 판정 POST /api/v1/supports/{id}/evaluate. 판정 입력은
 * 저장하지 않고 응답도 캐시하지 않아요.
 */
@RestController
class SupportController {

  private final SupportSearchService supports;

  SupportController(SupportSearchService supports) {
    this.supports = supports;
  }

  @PostMapping("/api/v1/supports/search")
  ResponseEntity<SupportSearchResponse> search(@Valid @RequestBody SupportSearchRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supports.search(request));
  }

  /** 본문은 사업공간 판정과 같아요 ({"applicant": …}). */
  @PostMapping("/api/v1/supports/{id}/evaluate")
  ResponseEntity<SupportEvaluationResponse> evaluate(
      @PathVariable UUID id, @Valid @RequestBody(required = false) SpaceEvaluateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(supports.evaluate(id, request == null ? null : request.applicant()));
  }
}
