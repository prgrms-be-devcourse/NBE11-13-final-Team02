package kr.mujuthaek.api.eligibility.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kr.mujuthaek.api.eligibility.repository.LoanProductStore;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.eligibility.rule.PrepareCost;
import org.springframework.stereotype.Service;

/** 보증금으로 준비할 돈 (받을 수 있는 대출을 뺀 금액). 입력은 저장하지 않아요. */
@Service
public class PrepareCostService {

  private final LoanProductStore products;
  private final Clock clock;

  public PrepareCostService(LoanProductStore products, Clock clock) {
    this.products = products;
    this.clock = clock;
  }

  public PrepareCost.Result prepare(Long depositWon, Applicant applicant) {
    return PrepareCost.calculate(depositWon, applicant, products.enabled(), LocalDate.now(clock));
  }

  /** 같은 판정 안에서 주택형마다 상품을 다시 읽지 않게, 상품 목록을 한 번 읽어 계산하는 함수를 줘요. */
  public java.util.function.Function<Long, PrepareCost.Result> preparer(Applicant applicant) {
    List<PrepareCost.LoanProduct> loaded = products.enabled();
    LocalDate today = LocalDate.now(clock);
    return deposit -> PrepareCost.calculate(deposit, applicant, loaded, today);
  }
}
