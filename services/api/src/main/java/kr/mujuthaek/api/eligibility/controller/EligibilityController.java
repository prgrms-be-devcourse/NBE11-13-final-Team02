package kr.mujuthaek.api.eligibility.controller;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.request.EvaluateRequest;
import kr.mujuthaek.api.eligibility.dto.request.PrepareCostRequest;
import kr.mujuthaek.api.eligibility.dto.request.SubscriptionScoreRequest;
import kr.mujuthaek.api.eligibility.dto.response.CriteriaResponse;
import kr.mujuthaek.api.eligibility.dto.response.NoticeEvaluationResponse;
import kr.mujuthaek.api.eligibility.rule.PrepareCost;
import kr.mujuthaek.api.eligibility.rule.SubscriptionScore;
import kr.mujuthaek.api.eligibility.service.CriteriaService;
import kr.mujuthaek.api.eligibility.service.NoticeEvaluationService;
import kr.mujuthaek.api.eligibility.service.PrepareCostService;
import kr.mujuthaek.api.eligibility.service.SubscriptionScoreService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 판정 API는 /eligibility 아래에 모아요 (판정 경로 B). 로그인 없이 써요. */
@RestController
@RequestMapping("/api/v1/eligibility")
class EligibilityController {

  /** 판정 응답은 사용자 조건으로 계산한 값이라 어디에도 캐시하지 않아요. */
  static final CacheControl NO_STORE = CacheControl.noStore();

  private final CriteriaService criteria;
  private final SubscriptionScoreService scores;
  private final NoticeEvaluationService evaluations;
  private final PrepareCostService prepareCost;

  EligibilityController(
      CriteriaService criteria,
      SubscriptionScoreService scores,
      NoticeEvaluationService evaluations,
      PrepareCostService prepareCost) {
    this.criteria = criteria;
    this.scores = scores;
    this.evaluations = evaluations;
    this.prepareCost = prepareCost;
  }

  /** 보증금으로 준비할 돈 (받을 수 있는 대출 고르기). 저장하지 않아요. */
  @PostMapping("/prepare-cost")
  ResponseEntity<PrepareCost.Result> prepareCost(@Valid @RequestBody PrepareCostRequest request) {
    return ResponseEntity.ok()
        .cacheControl(NO_STORE)
        .body(prepareCost.prepare(request.depositWon(), request.applicant().toApplicant()));
  }

  /** 이 공고에 대한 내 판정 (공급 대상별 결과, 조건별 근거). 저장하지 않아요. */
  @PostMapping("/notices/{id}/evaluate")
  ResponseEntity<NoticeEvaluationResponse> evaluateNotice(
      @PathVariable UUID id, @Valid @RequestBody EvaluateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(NO_STORE)
        .body(evaluations.evaluate(id, request.applicant().toApplicant()));
  }

  /** 청약 가점 (무주택 기간·부양가족·청약통장 가입 기간). 저장하지 않아요. */
  @PostMapping("/subscription-score")
  ResponseEntity<SubscriptionScore.Score> subscriptionScore(
      @Valid @RequestBody SubscriptionScoreRequest request) {
    return ResponseEntity.ok()
        .cacheControl(NO_STORE)
        .body(scores.score(request.applicant().toApplicant(), request.referenceDate()));
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
