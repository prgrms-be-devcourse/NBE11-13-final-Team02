package kr.mujuthaek.api.eligibility.controller;

import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.response.NoticeRegulationResponse;
import kr.mujuthaek.api.eligibility.service.NoticeRegulationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공고 지역의 규제지역 안내 (누구나, GET). 판정 결과 화면에 함께 보여 줄 안내이고 판정 값은 바꾸지 않아요.
 *
 * <pre>
 * GET /api/v1/eligibility/notices/{id}/regulation   걸린 규제지역(투기과열지구·조정대상지역·투기지역)과 안내 문장
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/eligibility/notices")
class NoticeRegulationController {

  private final NoticeRegulationService regulation;

  NoticeRegulationController(NoticeRegulationService regulation) {
    this.regulation = regulation;
  }

  @GetMapping("/{id}/regulation")
  NoticeRegulationResponse regulation(@PathVariable UUID id) {
    return regulation.forNotice(id);
  }
}
