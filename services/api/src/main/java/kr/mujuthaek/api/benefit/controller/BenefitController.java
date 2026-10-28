package kr.mujuthaek.api.benefit.controller;

import java.util.UUID;
import kr.mujuthaek.api.benefit.dto.response.BenefitDetailResponse;
import kr.mujuthaek.api.benefit.service.internal.BenefitDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 혜택 공개 정보. 내 조건 판정은 eligibility의 POST /api/v1/benefits/search · /{id}/evaluate예요. */
@RestController
@RequestMapping("/api/v1/benefits")
class BenefitController {

  private final BenefitDetailService details;

  BenefitController(BenefitDetailService details) {
    this.details = details;
  }

  /** 상세 (지원 내용, 신청 기간, 신청 방법·서류·문의, 자격 조건 원문과 근거, 원문 주소). */
  @GetMapping("/{id}")
  BenefitDetailResponse detail(@PathVariable UUID id) {
    return details.detail(id);
  }
}
