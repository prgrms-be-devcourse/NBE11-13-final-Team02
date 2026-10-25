package kr.mujuthaek.api.benefit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.mujuthaek.api.benefit.mapper.PolicyExtractedEvent;
import kr.mujuthaek.api.common.event.BenefitClosed;
import kr.mujuthaek.api.common.event.BenefitPublished;
import org.junit.jupiter.api.Test;

/** 정책 추출 결과 저장 규칙 (policy-extracted.draft.md 4장). */
class BenefitIngestionTest {

  private final BenefitFixture f = new BenefitFixture();

  private int count(String table, UUID benefitId) {
    return f.jdbc.queryForObject(
        "select count(*) from benefit." + table + " where benefit_id = ?",
        Integer.class,
        benefitId);
  }

  @Test
  void 처음_받은_정책은_한_줄과_지원_조건_줄로_저장하고_알린다() {
    UUID raw = UUID.randomUUID();
    f.ingest.apply(BenefitFixture.event(raw, 1, p -> {}));

    UUID id = f.repository.findByRawPolicyId(raw).orElseThrow().id();
    var row =
        f.jdbc.queryForMap(
            "select status, category, region_codes::text as regions, title from benefit.benefits"
                + " where id = ?",
            id);
    assertThat(row.get("status")).isEqualTo("OPEN");
    assertThat(row.get("category")).isEqualTo("HOUSING");
    assertThat(row.get("regions")).isEqualTo("{52730}");
    assertThat(count("benefit_supports", id)).isEqualTo(1);
    assertThat(count("benefit_conditions", id)).isEqualTo(9);
    assertThat(f.published)
        .singleElement()
        .isInstanceOfSatisfying(
            BenefitPublished.class, e -> assertThat(e.regionCode()).isEqualTo("52730"));
  }

  @Test
  void 큰_버전은_고치고_줄을_다시_넣고_같거나_작은_버전은_버린다() {
    UUID raw = UUID.randomUUID();
    f.ingest.apply(BenefitFixture.event(raw, 2, p -> {}));
    UUID id = f.repository.findByRawPolicyId(raw).orElseThrow().id();

    f.ingest.apply(BenefitFixture.event(raw, 1, p -> p.withObject("source").put("title", "예전 제목")));
    f.ingest.apply(
        BenefitFixture.event(
            raw,
            3,
            p -> {
              p.withObject("source").put("title", "새 제목");
              p.withArray("conditions").remove(8);
            }));

    assertThat(
            f.jdbc.queryForObject(
                "select title from benefit.benefits where id = ?", String.class, id))
        .isEqualTo("새 제목");
    assertThat(count("benefit_conditions", id)).isEqualTo(8);
    assertThat(f.repository.findByRawPolicyId(raw).orElseThrow().version()).isEqualTo(3);
    assertThat(f.published).hasSize(1); // 고친 건 다시 알리지 않아요
  }

  @Test
  void 범위_밖이거나_재처리면_알리지_않고_범위_밖은_숨긴다() {
    UUID hidden = f.benefit(p -> p.put("relevant", false));
    f.benefit(p -> p.put("reprocess", true));

    assertThat(f.published).isEmpty();
    assertThat(f.queries.forJudgment(hidden)).isEmpty();
  }

  @Test
  void 주거_혜택이_아니면_저장하지_않는다() {
    UUID raw = UUID.randomUUID();
    f.ingest.apply(BenefitFixture.event(raw, 1, p -> p.put("field", "STARTUP_SUPPORT")));

    assertThat(f.repository.findByRawPolicyId(raw)).isEmpty();
  }

  @Test
  void 계약에_맞지_않으면_실패() {
    String bad = BenefitFixture.event(UUID.randomUUID(), 1, p -> p.remove("supports"));

    assertThatThrownBy(() -> f.ingest.apply(bad))
        .isInstanceOf(PolicyExtractedEvent.InvalidEventException.class)
        .hasMessageContaining("SCHEMA");
  }

  @Test
  void 날짜가_지나면_상태를_바꾸고_마감은_알린다() {
    UUID id =
        f.benefit(
            p ->
                p.withObject("schedule")
                    .put("applyStart", "2026-09-01")
                    .put("applyEnd", "2026-10-21"));
    f.jdbc.update("update benefit.benefits set status = 'OPEN' where id = ?", id);
    f.published.clear();

    f.status.refresh();

    assertThat(
            f.jdbc.queryForObject(
                "select status from benefit.benefits where id = ?", String.class, id))
        .isEqualTo("CLOSED");
    assertThat(f.published)
        .filteredOn(e -> e instanceof BenefitClosed c && c.benefitId().equals(id))
        .hasSize(1);
  }
}
