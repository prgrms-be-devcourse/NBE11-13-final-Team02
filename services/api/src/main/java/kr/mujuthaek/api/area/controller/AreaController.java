package kr.mujuthaek.api.area.controller;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.area.domain.AreaCriteria;
import kr.mujuthaek.api.area.domain.SiteDetail;
import kr.mujuthaek.api.area.domain.SiteScore;
import kr.mujuthaek.api.area.dto.request.AreaScoreRequest;
import kr.mujuthaek.api.area.service.AreaScoreService;
import kr.mujuthaek.api.area.service.AreaSiteService;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * area 조회 API (API 문서 area 모듈). 위치별 지표·항목 점수는 공개 GET, 총점·통근은 사용자 가중치·직장 위치가 들어가서 요청 때 POST로 계산하고
 * 저장·캐시하지 않아요(판정 API와 같은 no-store). siteId는 area 위치 ID예요.
 */
@RestController
@RequestMapping("/api/v1/areas")
class AreaController {

  private static final CacheControl NO_STORE = CacheControl.noStore();

  private final AreaSiteService sites;
  private final AreaScoreService scores;

  AreaController(AreaSiteService sites, AreaScoreService scores) {
    this.sites = sites;
    this.scores = scores;
  }

  @GetMapping("/sites/{siteId}")
  SiteDetail site(@PathVariable UUID siteId) {
    return sites.site(siteId).orElseThrow(() -> new CustomException(ErrorCode.AREA_SITE_NOT_FOUND));
  }

  /** 응답 items는 받은 순서이고, 없는 위치는 빠져요. */
  @PostMapping("/score")
  ResponseEntity<Map<String, List<SiteScore>>> score(@Valid @RequestBody AreaScoreRequest request) {
    List<SiteScore> items =
        scores.score(
            request.siteIds(),
            request.priorities() == null ? Map.of() : request.priorities().toMap(),
            request.workplace() == null ? null : request.workplace().toCoordinate());
    return ResponseEntity.ok().cacheControl(NO_STORE).body(Map.of("items", items));
  }

  @GetMapping("/criteria")
  ResponseEntity<AreaCriteria> criteria() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
        .body(sites.criteria());
  }
}
