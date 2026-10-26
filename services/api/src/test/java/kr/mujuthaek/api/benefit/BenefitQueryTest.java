package kr.mujuthaek.api.benefit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.benefit.service.BenefitForJudgment;
import org.junit.jupiter.api.Test;

/** 판정 후보 조회: 지역 겹침, 숨김·마감 제외, 금액은 원. */
class BenefitQueryTest {

  private final BenefitFixture f = new BenefitFixture();

  private static java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> region(
      String scope, String... codes) {
    return p -> {
      var source = p.withObject("source");
      source.put("regionScope", scope);
      var arr = source.putArray("regionCodes");
      for (String c : codes) arr.add(c);
    };
  }

  private List<UUID> ids(List<String> filter) {
    return f.queries.openForJudgment(filter).stream().map(BenefitForJudgment::id).toList();
  }

  @Test
  void 찾아볼_지역과_겹치는_혜택과_전국_혜택만_준다() {
    UUID mapo = f.benefit(region("SIGUNGU", "11440"));
    UUID seoul = f.benefit(region("SIDO", "11"));
    UUID busan = f.benefit(region("SIGUNGU", "26350"));
    UUID nationwide = f.benefit(region("NATIONWIDE"));
    UUID closed =
        f.benefit(
            region("SIGUNGU", "11440")
                .andThen(p -> p.withObject("schedule").put("applyEnd", "2026-10-01")));

    assertThat(ids(List.of("11440")))
        .contains(mapo, seoul, nationwide)
        .doesNotContain(busan, closed);
    assertThat(ids(List.of("11"))).contains(mapo, seoul, nationwide).doesNotContain(busan);
    assertThat(ids(List.of())).contains(mapo, seoul, busan, nationwide).doesNotContain(closed);
  }

  @Test
  void 지원_금액은_원으로_조건은_원문과_함께_준다() {
    UUID id = f.benefit(p -> {});

    BenefitForJudgment b = f.queries.forJudgment(id).orElseThrow();
    assertThat(b.supports().getFirst().amountMaxWon()).isEqualTo(200_000L);
    assertThat(b.supports().getFirst().amountPer()).isEqualTo("MONTH");
    assertThat(b.conditions()).hasSize(9);
    assertThat(b.conditions().getFirst().valueJson()).contains("\"min\"").contains("18");
    assertThat(b.conditions().get(5).valueJson()).isNull();
  }
}
