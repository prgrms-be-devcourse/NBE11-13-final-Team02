package kr.mujuthaek.api.area.controller;

import java.util.UUID;
import kr.mujuthaek.api.area.domain.CommercialScore;
import kr.mujuthaek.api.area.service.AreaCommercialService;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공공 상가 위치의 상권 점수 (API 문서 GET /areas/commercial/{siteId}?industry=). 공개 GET이에요. siteId는 area 위치
 * ID(공고 위치의 siteId). 창업 공간·회의실은 교통만 보면 돼서 GET /areas/sites/{siteId}를 써요.
 */
@RestController
@RequestMapping("/api/v1/areas/commercial")
class AreaCommercialController {

  private final AreaCommercialService commercial;

  AreaCommercialController(AreaCommercialService commercial) {
    this.commercial = commercial;
  }

  @GetMapping("/{siteId}")
  CommercialScore score(
      @PathVariable UUID siteId, @RequestParam(required = false) String industry) {
    return commercial
        .score(siteId, industry)
        .orElseThrow(() -> new CustomException(ErrorCode.AREA_SITE_NOT_FOUND));
  }
}
