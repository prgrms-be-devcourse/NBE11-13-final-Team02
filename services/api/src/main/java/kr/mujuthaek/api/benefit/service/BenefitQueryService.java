package kr.mujuthaek.api.benefit.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.benefit.repository.BenefitRepository;
import kr.mujuthaek.api.benefit.repository.BenefitRepository.Row;
import org.springframework.stereotype.Service;

/** 다른 모듈(eligibility)이 판정에 쓰는 혜택 조회. 숨김·마감 혜택은 주지 않아요. */
@Service
public class BenefitQueryService {

  /** 만 원 → 원 */
  public static final long MANWON = 10_000L;

  private final BenefitRepository benefits;

  public BenefitQueryService(BenefitRepository benefits) {
    this.benefits = benefits;
  }

  /** 신청할 수 있거나 곧 열리는 주거 혜택. regionCodes가 비면 전체, 있으면 전국 + 그 지역과 겹치는 혜택. */
  public List<BenefitForJudgment> openForJudgment(Collection<String> regionCodes) {
    return benefits.openBenefits(regionCodes).stream()
        .map(BenefitQueryService::toJudgment)
        .toList();
  }

  /** 혜택 하나 (숨김이면 없음). */
  public Optional<BenefitForJudgment> forJudgment(UUID id) {
    return benefits.find(id).map(BenefitQueryService::toJudgment);
  }

  static BenefitForJudgment toJudgment(Row r) {
    return new BenefitForJudgment(
        r.id(),
        r.title(),
        r.agency(),
        r.category(),
        r.summary(),
        r.regionScope(),
        r.regionCodes(),
        r.regionNames(),
        r.applyStart(),
        r.applyEnd(),
        r.applyAlways(),
        r.needsReview(),
        r.supports().stream()
            .map(
                s ->
                    new BenefitForJudgment.Support(
                        s.kind(),
                        s.amountMax() == null ? null : s.amountMax() * MANWON,
                        s.amountPer(),
                        s.months(),
                        s.ratePct(),
                        s.rawText()))
            .toList(),
        r.conditions().stream()
            .map(
                c ->
                    new BenefitForJudgment.Condition(
                        c.type(), c.valueJson(), c.group(), c.known(), c.rawText(), c.evidence()))
            .toList());
  }
}
