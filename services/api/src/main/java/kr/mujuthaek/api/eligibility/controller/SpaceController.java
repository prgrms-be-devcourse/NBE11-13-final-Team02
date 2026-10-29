package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.request.SpaceEvaluateRequest;
import kr.mujuthaek.api.eligibility.dto.request.SpaceSearchRequest;
import kr.mujuthaek.api.eligibility.dto.response.SpaceEvaluationResponse;
import kr.mujuthaek.api.eligibility.dto.response.SpaceSearchResponse;
import kr.mujuthaek.api.eligibility.service.SpaceSearchService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사업공간 맞춤 목록 POST /api/v1/spaces/search 와 공간 하나의 판정 POST /api/v1/spaces/{id}/evaluate. 상세 내용은 공고
 * 상세(GET /notices/{id})를 써요. 판정 입력은 저장하지 않고 응답도 캐시하지 않아요.
 */
@RestController
class SpaceController {

  private final SpaceSearchService spaces;

  SpaceController(SpaceSearchService spaces) {
    this.spaces = spaces;
  }

  @PostMapping("/api/v1/spaces/search")
  ResponseEntity<SpaceSearchResponse> search(@Valid @RequestBody SpaceSearchRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(spaces.search(request));
  }

  @PostMapping("/api/v1/spaces/{id}/evaluate")
  ResponseEntity<SpaceEvaluationResponse> evaluate(
      @PathVariable UUID id, @Valid @RequestBody(required = false) SpaceEvaluateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            request == null ? spaces.evaluate(id, null) : spaces.evaluate(id, request.applicant()));
  }
}
