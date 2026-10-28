package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.request.BenefitSearchRequest;
import kr.mujuthaek.api.eligibility.dto.request.EvaluateRequest;
import kr.mujuthaek.api.eligibility.dto.response.BenefitEvaluationResponse;
import kr.mujuthaek.api.eligibility.dto.response.BenefitSearchResponse;
import kr.mujuthaek.api.eligibility.service.BenefitEvaluationService;
import kr.mujuthaek.api.eligibility.service.BenefitSearchService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 혜택 맞춤 목록·판정. 판정이 섞여서 eligibility에 있어요 (공개 상세는 benefit의 GET /api/v1/benefits/{id}). 판정 입력은 저장하지 않고
 * 응답도 캐시하지 않아요.
 */
@RestController
class BenefitSearchController {

  private final BenefitSearchService search;
  private final BenefitEvaluationService evaluations;

  BenefitSearchController(BenefitSearchService search, BenefitEvaluationService evaluations) {
    this.search = search;
    this.evaluations = evaluations;
  }

  @PostMapping("/api/v1/benefits/search")
  ResponseEntity<BenefitSearchResponse> search(@Valid @RequestBody BenefitSearchRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(request));
  }

  @PostMapping("/api/v1/benefits/{id}/evaluate")
  ResponseEntity<BenefitEvaluationResponse> evaluate(
      @PathVariable UUID id, @Valid @RequestBody EvaluateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(evaluations.evaluate(id, request.applicant().toApplicant()));
  }
}
