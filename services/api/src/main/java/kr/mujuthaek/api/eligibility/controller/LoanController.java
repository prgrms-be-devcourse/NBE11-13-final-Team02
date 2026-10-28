package kr.mujuthaek.api.eligibility.controller;

import kr.mujuthaek.api.eligibility.dto.request.LoanEvaluateRequest;
import kr.mujuthaek.api.eligibility.model.LoanResult;
import kr.mujuthaek.api.eligibility.service.LoanQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대출 자격 판정 API (로그인 없이). 공고와 상관없이 주택도시기금 대출 상품마다 받을 수 있는지(가능 / 확인 필요 / 불가)와 이유를 줘요. 소득·자산은 POST
 * 본문으로만 받고, 입력과 결과는 저장하지 않아요. 판정 결과라서 캐시하지 않아요 (no-store).
 */
@RestController
@RequestMapping("/api/v1/eligibility")
class LoanController {

  private final LoanQueryService loans;

  LoanController(LoanQueryService loans) {
    this.loans = loans;
  }

  /**
   * 대출 자격 판정. 예: POST /api/v1/eligibility/loans/evaluate { "profile": { "birthDate": "1999-05-05",
   * "loan": { "annualIncomeWon": 40000000, "netAssetWon": 100000000 } }, "asOf": "2026-10-21" }
   */
  @PostMapping("/loans/evaluate")
  ResponseEntity<LoanResult> evaluate(@RequestBody LoanEvaluateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(loans.judge(request.profile(), request.asOf()));
  }
}
