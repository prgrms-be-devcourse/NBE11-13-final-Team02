package kr.mujuthaek.api.benefit.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.benefit.repository.BenefitRepository;
import kr.mujuthaek.api.benefit.repository.BenefitRepository.Row;
import org.springframework.stereotype.Service;

/**
 * 다른 모듈이 쓰는 혜택 조회. 판정용(eligibility: openForJudgment·forJudgment)은 숨김·마감 혜택을 주지 않고, 알림용
 * (notification: findSummary·findOpenClosingOn)은 요약만 줘요.
 */
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

  /**
   * 판정 후보와, 그중 나이 조건만으로 지원 불가가 확실한 혜택(DB에서 골라 지원 내용·조건을 읽지 않은 것).
   *
   * @param benefits 판정 후보 (마감일 순). 나이로 걸러진 혜택도 순서대로 들어 있고 지원 내용·조건이 비어 있어요
   * @param ruledOutByAge 나이로 걸러진 혜택 ID
   */
  public record OpenBenefits(
      List<BenefitForJudgment> benefits, java.util.Set<UUID> ruledOutByAge) {}

  /**
   * {@link #openForJudgment(Collection)}과 같은 혜택을 주되, 지원자 나이로 지원 불가가 확실한 혜택은 DB에서 골라 조건을 읽지 않아요.
   *
   * @param ages 지원자 만 나이 [가장 어린, 가장 많은]. null이면 거르지 않아요
   */
  public OpenBenefits openForJudgment(Collection<String> regionCodes, int[] ages) {
    var open = benefits.openBenefits(regionCodes, ages);
    return new OpenBenefits(
        open.rows().stream().map(BenefitQueryService::toJudgment).toList(), open.ruledOutByAge());
  }

  /** 혜택 하나 (숨김이면 없음). */
  public Optional<BenefitForJudgment> forJudgment(UUID id) {
    return benefits.find(id).map(BenefitQueryService::toJudgment);
  }

  /** 혜택 하나의 요약 (알림용). 숨김(HIDDEN)도 줘요. 없으면 빈 값 */
  public Optional<BenefitSummary> findSummary(UUID id) {
    return benefits.summary(id).map(BenefitQueryService::toSummary);
  }

  /** 혜택 여러 개의 요약을 한 번에 (관심 목록용). 혜택 ID → 요약, 없는 ID는 빠져요. 숨김(HIDDEN)도 줘요 */
  public java.util.Map<UUID, BenefitSummary> findSummaries(Collection<UUID> ids) {
    java.util.Map<UUID, BenefitSummary> out = new java.util.HashMap<>();
    for (var row : benefits.summaries(ids.stream().distinct().toList())) {
      out.put(row.id(), toSummary(row));
    }
    return out;
  }

  /**
   * 수집 정책 ID(rawPolicyId)로 혜택 하나의 요약 (알림 담당: BenefitReportAnswered의 policyId가 이 ID라서). 숨김도 줘요. 없으면
   * 빈 값
   */
  /** 혜택 ID의 정책 원본 ID (제보·검수는 원본 ID로 다뤄요). 혜택 ID가 아니면 비어 있어요 */
  public Optional<UUID> findRawPolicyId(UUID benefitId) {
    return benefits.rawPolicyId(benefitId);
  }

  public Optional<BenefitSummary> findSummaryByRawPolicyId(UUID rawPolicyId) {
    return benefits.summaryByRawPolicyId(rawPolicyId).map(BenefitQueryService::toSummary);
  }

  /** 마감일이 dates 중 하나이고 오늘 접수 중인 혜택 (마감 D-3·D-1 알림용). 숨김·상시·시작 전 제외, 마감일 순 */
  public List<BenefitSummary> findOpenClosingOn(Collection<LocalDate> dates, LocalDate today) {
    return benefits.openClosingOn(dates, today).stream()
        .map(BenefitQueryService::toSummary)
        .toList();
  }

  private static BenefitSummary toSummary(BenefitRepository.SummaryRow r) {
    return new BenefitSummary(
        r.id(),
        r.field(),
        r.version(),
        r.title(),
        r.agency(),
        r.status(),
        r.applyStart(),
        r.applyEnd(),
        r.applyAlways(),
        r.regionScope(),
        r.regionCodes(),
        r.detailUrl());
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
