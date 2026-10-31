package kr.mujuthaek.api.eligibility.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujuthaek.api.benefit.service.BenefitForJudgment;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.eligibility.dto.response.BenefitEvaluationResponse;
import kr.mujuthaek.api.eligibility.dto.response.BenefitSearchResponse;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.eligibility.rule.Bands;
import kr.mujuthaek.api.eligibility.rule.IncomeBenchmarks;
import kr.mujuthaek.api.eligibility.rule.benefit.BenefitJudge;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 혜택 판정 (benefit의 조건을 읽어 eligibility 규칙으로). 기준 소득은 요청마다 한 번만 읽어요. 판정 입력은 저장하지 않아요. eligibility →
 * benefit 호출이라 이 모듈에 둬요 (benefit은 다른 모듈을 부르지 않아요).
 */
@Service
public class BenefitEvaluationService {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

  /** 갚아야 하는 지원 (1년에 받는 돈에 넣지 않아요). */
  private static final Set<String> LOANS = Set.of("DEPOSIT_LOAN", "PURCHASE_LOAN", "LOAN");

  /** 공고의 준비할 돈에 반영할 수 있는 지원. */
  private static final Set<String> PREPARE_COST = Set.of("DEPOSIT_LOAN", "PURCHASE_LOAN");

  private final BenefitQueryService benefits;
  private final CriteriaService criteria;
  private final JsonMapper json;
  private final Clock clock;

  public BenefitEvaluationService(
      BenefitQueryService benefits, CriteriaService criteria, JsonMapper json, Clock clock) {
    this.benefits = benefits;
    this.criteria = criteria;
    this.json = json;
    this.clock = clock;
  }

  /** 판정한 혜택 한 건. */
  public record Judged(BenefitForJudgment benefit, BenefitJudge.Result result, long annualWon) {}

  /**
   * 찾아볼 지역의 열린 혜택을 모두 판정해요 (지원 불가 포함). 나이만으로 지원 불가가 확실한 혜택은 DB가 골라 주어 조건을 읽거나 판정하지 않고 지원 불가로 둬요
   * (BenefitJudge와 같은 규칙이라 결과는 같아요).
   */
  public List<Judged> judgeAll(List<String> regionCodes, Applicant applicant) {
    var judge = evaluator(applicant);
    int[] ages =
        applicant.birthMonth() == null
            ? null
            : Bands.ages(applicant.birthMonth(), LocalDate.now(clock.withZone(KOREA)));
    var open = benefits.openForJudgment(regionCodes, ages);
    return open.benefits().stream()
        .map(b -> open.ruledOutByAge().contains(b.id()) ? ruledOutByAge(b) : judge.apply(b))
        .toList();
  }

  /** 나이 조건 그룹이 불충족이라 지원 불가 (목록은 지원 불가를 세기만 하고 보여 주지 않아요) */
  private static Judged ruledOutByAge(BenefitForJudgment b) {
    return new Judged(
        b,
        new BenefitJudge.Result(
            kr.mujuthaek.api.eligibility.rule.Overall.INELIGIBLE,
            new BenefitJudge.Summary(0, 0, 0, 0),
            List.of(),
            List.of(),
            null),
        0);
  }

  public BenefitEvaluationResponse evaluate(UUID id, Applicant applicant) {
    BenefitForJudgment b =
        benefits
            .forJudgment(id)
            .orElseThrow(() -> new CustomException(ErrorCode.BENEFIT_NOT_FOUND));
    Judged j = evaluator(applicant).apply(b);
    var r = j.result();
    return new BenefitEvaluationResponse(
        b.id(),
        r.overall(),
        r.reason(),
        r.summary(),
        r.groups().stream()
            .map(g -> new BenefitEvaluationResponse.Group(g.group(), g.status().name(), g.items()))
            .toList(),
        r.notes(),
        j.annualWon());
  }

  /** 요청 하나에서 여러 혜택을 판정할 때 기준 소득을 한 번만 읽어요. */
  Function<BenefitForJudgment, Judged> evaluator(Applicant applicant) {
    IncomeBenchmarks benchmarks = criteria.benchmarks();
    LocalDate today = LocalDate.now(clock.withZone(KOREA));
    return b ->
        new Judged(
            b,
            BenefitJudge.judge(
                b.conditions().stream().map(this::condition).toList(),
                b.needsReview(),
                applicant,
                today,
                benchmarks),
            annualWon(b));
  }

  @SuppressWarnings("unchecked")
  private BenefitJudge.Condition condition(BenefitForJudgment.Condition c) {
    Map<String, Object> value =
        c.valueJson() == null ? Map.of() : json.readValue(c.valueJson(), Map.class);
    return new BenefitJudge.Condition(c.type(), value, c.group(), c.known(), c.rawText());
  }

  /**
   * 1년에 받을 수 있는 돈: 금액이 정해진 지원만 (대출 빼고). 월 지원은 최대 12개월.
   *
   * <p>같은 종류의 지원이 여러 줄이면 연차별 금액(1년차·2년차), 유형별 금액(가형·나형), 같은 지원 중복인 경우가 많아서 더하지 않고 가장 큰 것만 넣어요. 다른
   * 종류끼리만 더해요 (2026-10-29 QA: 1~3년차 월 지원을 다 더해 1년 3,600만 원이 되던 것).
   */
  static long annualWon(BenefitForJudgment b) {
    Map<String, Long> byKind = new HashMap<>();
    for (var s : b.supports()) {
      if (s.amountMaxWon() == null || LOANS.contains(s.kind())) continue;
      long won =
          switch (s.amountPer() == null ? "ONCE" : s.amountPer()) {
            case "MONTH" -> s.amountMaxWon() * Math.min(s.months() == null ? 12 : s.months(), 12);
            default -> s.amountMaxWon();
          };
      byKind.merge(s.kind(), won, Math::max);
    }
    return byKind.values().stream().mapToLong(Long::longValue).sum();
  }

  /** 목록 한 줄. */
  BenefitSearchResponse.Item item(Judged j) {
    BenefitForJudgment b = j.benefit();
    LocalDate today = LocalDate.now(clock.withZone(KOREA));
    return new BenefitSearchResponse.Item(
        b.id(),
        b.title(),
        b.agency(),
        b.category(),
        b.summary(),
        b.regionNames(),
        amountText(b),
        j.result().overall() == kr.mujuthaek.api.eligibility.rule.Overall.INELIGIBLE
            ? 0
            : j.annualWon(),
        applyStatus(b, today),
        b.applyEnd(),
        b.applyAlways() || b.applyEnd() == null
            ? null
            : (int) ChronoUnit.DAYS.between(today, b.applyEnd()),
        j.result().overall(),
        b.supports().stream().anyMatch(s -> PREPARE_COST.contains(s.kind())),
        b.needsReview());
  }

  private static String applyStatus(BenefitForJudgment b, LocalDate today) {
    if (b.applyAlways()) return "ALWAYS";
    if (b.applyStart() != null && today.isBefore(b.applyStart())) return "UPCOMING";
    if (b.applyEnd() != null && b.applyEnd().isBefore(today)) return "CLOSED";
    return "APPLYING";
  }

  /** 지원 내용 한 줄 ("월 최대 20만 원 · 12개월", "최대 1억 원 대출", "이자 50% 지원"). 첫 지원 내용 기준이에요. */
  static String amountText(BenefitForJudgment b) {
    if (b.supports().isEmpty()) return null;
    var s = b.supports().getFirst();
    String per =
        switch (s.amountPer() == null ? "" : s.amountPer()) {
          case "MONTH" -> "월 ";
          case "YEAR" -> "연 ";
          default -> "";
        };
    String amount = s.amountMaxWon() == null ? null : per + "최대 " + won(s.amountMaxWon());
    if (amount != null && LOANS.contains(s.kind())) amount += " 대출";
    String months = s.months() == null ? null : s.months() + "개월";
    String rate =
        s.ratePct() == null
            ? null
            : (LOANS.contains(s.kind()) ? "이자 " : "")
                + s.ratePct().stripTrailingZeros().toPlainString()
                + "% 지원";
    String text =
        java.util.stream.Stream.of(amount, months, amount == null ? rate : null)
            .filter(java.util.Objects::nonNull)
            .reduce((x, y) -> x + " · " + y)
            .orElse(null);
    return text != null ? text : s.rawText();
  }

  /** 200000 → "20만 원", 100000000 → "1억 원", 15000000 → "1,500만 원" */
  static String won(long won) {
    long man = won / 10_000;
    if (man == 0) return String.format("%,d원", won);
    long eok = man / 10_000;
    long rest = man % 10_000;
    if (eok == 0) return String.format("%,d만 원", man);
    return rest == 0 ? eok + "억 원" : String.format("%d억 %,d만 원", eok, rest);
  }
}
