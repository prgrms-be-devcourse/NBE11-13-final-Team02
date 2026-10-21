package kr.mujuthaek.api.eligibility.rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 판정 틀 전체를 판정해요: 공급 대상(그룹)마다 공통 조건 + 그 대상의 조건을 모두 보고, 대상 중 하나라도 지원 가능하면 공고는 지원 가능이에요.
 *
 * <p>결과에는 사용자가 보낸 값을 넣지 않아요.
 */
public final class Judge {

  private Judge() {}

  /**
   * @param missingInputs 지원 불가가 아닌 대상에서, 넣으면 판정이 바뀌는 입력 칸 (화면의 "입력" 버튼)
   */
  public record Result(Overall overall, List<Target> targets, List<String> missingInputs) {}

  /**
   * @param summary 조건 수 (화면의 "4개 조건 중 3개 충족 · 1개 확인 필요")
   */
  public record Target(
      String id, String name, Overall overall, Summary summary, List<Item> items) {}

  public record Summary(int total, int met, int notMet, int unknown) {}

  public static Result evaluate(Judgment judgment, Applicant applicant) {
    List<Item> common =
        judgment.common().stream()
            .map(c -> CriterionJudge.judge(c, applicant, judgment.referenceDate()))
            .toList();
    List<Target> targets = new ArrayList<>();
    Set<String> missing = new LinkedHashSet<>();
    for (Judgment.Group g : judgment.groups()) {
      List<Item> items = new ArrayList<>(common);
      g.criteria()
          .forEach(c -> items.add(CriterionJudge.judge(c, applicant, judgment.referenceDate())));
      Overall overall = Overall.of(Status.all(items.stream().map(Item::status).toList()));
      targets.add(new Target(g.id(), g.name(), overall, summary(items), List.copyOf(items)));
      if (overall != Overall.INELIGIBLE) {
        items.forEach(i -> missing.addAll(i.needs()));
      }
    }
    Overall overall =
        targets.isEmpty()
            ? Overall.of(Status.all(common.stream().map(Item::status).toList()))
            : Overall.best(targets.stream().map(Target::overall).toList());
    return new Result(overall, List.copyOf(targets), List.copyOf(missing));
  }

  static Summary summary(List<Item> items) {
    int met = 0;
    int notMet = 0;
    int unknown = 0;
    for (Item i : items) {
      switch (i.status()) {
        case MET -> met++;
        case NOT_MET -> notMet++;
        case UNKNOWN -> unknown++;
      }
    }
    return new Summary(items.size(), met, notMet, unknown);
  }
}
