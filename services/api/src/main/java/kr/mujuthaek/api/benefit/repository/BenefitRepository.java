package kr.mujuthaek.api.benefit.repository;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.benefit.domain.BenefitStatus;
import kr.mujuthaek.api.benefit.mapper.PolicyExtractedEvent;
import kr.mujuthaek.api.benefit.mapper.PolicyExtractedEvent.Condition;
import kr.mujuthaek.api.benefit.mapper.PolicyExtractedEvent.Support;
import kr.mujuthaek.api.common.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 혜택 읽기·쓰기 (benefit.benefits + benefit_supports + benefit_conditions). */
@Repository
public class BenefitRepository {

  private final JdbcClient jdbc;

  public BenefitRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 저장된 혜택의 ID와 반영한 수집 버전. */
  public record Saved(UUID id, int version) {}

  /** 혜택 한 줄 (지원 내용·조건 포함). */
  public record Row(
      UUID id,
      String sourceCode,
      String field,
      String category,
      String title,
      String agency,
      String regionScope,
      List<String> regionNames,
      List<String> regionCodes,
      List<String> categories,
      String detailUrl,
      String applyUrl,
      String summary,
      LocalDate applyStart,
      LocalDate applyEnd,
      boolean applyAlways,
      LocalDate businessStart,
      LocalDate businessEnd,
      String scheduleText,
      String howToApply,
      List<String> documents,
      String contact,
      String status,
      boolean needsReview,
      List<Support> supports,
      List<Condition> conditions) {}

  public Optional<Saved> findByRawPolicyId(UUID rawPolicyId) {
    return jdbc.sql("select id, source_version from benefit.benefits where raw_policy_id = ?")
        .param(rawPolicyId)
        .query((rs, i) -> new Saved(rs.getObject("id", UUID.class), rs.getInt("source_version")))
        .optional();
  }

  /** 새로 넣거나(id가 없으면) 고쳐요. 지원 내용·조건 줄은 지우고 다시 넣어요 (줄 단위 비교는 하지 않아요). */
  public UUID save(
      UUID id, PolicyExtractedEvent e, String category, BenefitStatus status, Instant now) {
    UUID benefitId = id == null ? UuidV7.next() : id;
    jdbc.sql(
            "insert into benefit.benefits (id, raw_policy_id, source_code, provider, source_policy_id,"
                + " source_version, field, category, title, agency, region_scope, region_names,"
                + " region_codes, categories, detail_url, apply_url, summary, apply_start, apply_end,"
                + " apply_always, business_start, business_end, schedule_text, how_to_apply,"
                + " documents, contact, status, relevant, confidence, needs_review, review_reasons,"
                + " has_document, extracted_at, created_at, updated_at) values (:id, :raw, :source,"
                + " :provider, :policyId, :version, :field, :category, :title, :agency, :scope,"
                + " cast(:regionNames as text[]), cast(:regionCodes as text[]),"
                + " cast(:categories as text[]), :detailUrl, :applyUrl, :summary, :applyStart,"
                + " :applyEnd, :applyAlways, :businessStart, :businessEnd, :scheduleText,"
                + " :howToApply, cast(:documents as text[]), :contact, :status, :relevant,"
                + " :confidence, :needsReview, cast(:reviewReasons as text[]), :hasDocument,"
                + " :extractedAt, :now, :now) on conflict (raw_policy_id) do update set"
                + " source_code = excluded.source_code, provider = excluded.provider,"
                + " source_policy_id = excluded.source_policy_id,"
                + " source_version = excluded.source_version, field = excluded.field,"
                + " category = excluded.category, title = excluded.title, agency = excluded.agency,"
                + " region_scope = excluded.region_scope, region_names = excluded.region_names,"
                + " region_codes = excluded.region_codes, categories = excluded.categories,"
                + " detail_url = excluded.detail_url, apply_url = excluded.apply_url,"
                + " summary = excluded.summary, apply_start = excluded.apply_start,"
                + " apply_end = excluded.apply_end, apply_always = excluded.apply_always,"
                + " business_start = excluded.business_start, business_end = excluded.business_end,"
                + " schedule_text = excluded.schedule_text, how_to_apply = excluded.how_to_apply,"
                + " documents = excluded.documents, contact = excluded.contact,"
                + " status = excluded.status, relevant = excluded.relevant,"
                + " confidence = excluded.confidence, needs_review = excluded.needs_review,"
                + " review_reasons = excluded.review_reasons, has_document = excluded.has_document,"
                + " extracted_at = excluded.extracted_at, updated_at = excluded.updated_at")
        .param("id", benefitId)
        .param("raw", e.rawPolicyId())
        .param("source", e.sourceCode())
        .param("provider", e.provider())
        .param("policyId", e.policyId())
        .param("version", e.version())
        .param("field", e.field())
        .param("category", category)
        .param("title", e.title())
        .param("agency", e.agency())
        .param("scope", e.regionScope())
        .param("regionNames", e.regionNames().toArray(String[]::new))
        .param("regionCodes", e.regionCodes().toArray(String[]::new))
        .param("categories", e.categories().toArray(String[]::new))
        .param("detailUrl", e.detailUrl())
        .param("applyUrl", e.applyUrl())
        .param("summary", e.summary())
        .param("applyStart", e.applyStart())
        .param("applyEnd", e.applyEnd())
        .param("applyAlways", e.applyAlways())
        .param("businessStart", e.businessStart())
        .param("businessEnd", e.businessEnd())
        .param("scheduleText", e.scheduleText())
        .param("howToApply", e.howToApply())
        .param("documents", e.documents().toArray(String[]::new))
        .param("contact", e.contact())
        .param("status", status.name())
        .param("relevant", e.relevant())
        .param("confidence", e.confidence())
        .param("needsReview", e.needsReview())
        .param("reviewReasons", e.reviewReasons().toArray(String[]::new))
        .param("hasDocument", e.hasDocument())
        .param("extractedAt", Timestamp.from(e.extractedAt()))
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql("delete from benefit.benefit_supports where benefit_id = ?").param(benefitId).update();
    jdbc.sql("delete from benefit.benefit_conditions where benefit_id = ?")
        .param(benefitId)
        .update();
    for (int i = 0; i < e.supports().size(); i++) {
      Support s = e.supports().get(i);
      jdbc.sql(
              "insert into benefit.benefit_supports (id, benefit_id, ordinal, kind, amount_max,"
                  + " amount_per, months, rate_pct, raw_text, evidence) values (?, ?, ?, ?, ?, ?, ?,"
                  + " ?, ?, ?)")
          .params(
              UuidV7.next(),
              benefitId,
              i,
              s.kind(),
              s.amountMax(),
              s.amountPer(),
              s.months(),
              s.ratePct(),
              s.rawText(),
              s.evidence())
          .update();
    }
    for (int i = 0; i < e.conditions().size(); i++) {
      Condition c = e.conditions().get(i);
      jdbc.sql(
              "insert into benefit.benefit_conditions (id, benefit_id, ordinal, type, value, grp,"
                  + " known, raw_text, evidence, confidence, origin) values (?, ?, ?, ?,"
                  + " cast(? as jsonb), ?, ?, ?, ?, ?, ?)")
          .params(
              UuidV7.next(),
              benefitId,
              i,
              c.type(),
              c.valueJson(),
              c.group(),
              c.known(),
              c.rawText(),
              c.evidence(),
              c.confidence(),
              c.origin())
          .update();
    }
    return benefitId;
  }

  /** 상태를 다시 계산할 혜택 (숨김 빼고). */
  public record StatusRow(
      UUID id, String status, boolean applyAlways, LocalDate applyStart, LocalDate applyEnd) {}

  public List<StatusRow> visibleStatuses() {
    return jdbc.sql(
            "select id, status, apply_always, apply_start, apply_end from benefit.benefits"
                + " where status <> 'HIDDEN'")
        .query(
            (rs, i) ->
                new StatusRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getBoolean("apply_always"),
                    day(rs, "apply_start"),
                    day(rs, "apply_end")))
        .list();
  }

  public void updateStatus(UUID id, BenefitStatus status, Instant now) {
    jdbc.sql("update benefit.benefits set status = ?, updated_at = ? where id = ?")
        .params(status.name(), Timestamp.from(now), id)
        .update();
  }

  /**
   * 판정 후보: 숨김·마감을 빼고, regionCodes가 있으면 전국 혜택 + 그 지역과 겹치는 혜택만 (시도 2자리는 그 안 시군구와 겹쳐요). field는
   * BENEFIT만.
   */
  public List<Row> openBenefits(Collection<String> regionCodes) {
    String[] regions = regionCodes.toArray(String[]::new);
    List<Row> rows =
        jdbc.sql(
                "select * from benefit.benefits b where b.field = 'BENEFIT'"
                    + " and b.status in ('UPCOMING', 'OPEN') and (cardinality(cast(:regions as"
                    + " text[])) = 0 or b.region_scope = 'NATIONWIDE' or exists (select 1 from"
                    + " unnest(b.region_codes) r, unnest(cast(:regions as text[])) f where r like f"
                    + " || '%' or f like r || '%')) order by b.apply_end nulls last, b.id")
            .param("regions", regions)
            .query(BenefitRepository::row)
            .list();
    return withLines(rows);
  }

  public Optional<Row> find(UUID id) {
    List<Row> rows =
        jdbc.sql("select * from benefit.benefits where id = ? and status <> 'HIDDEN'")
            .param(id)
            .query(BenefitRepository::row)
            .list();
    return withLines(rows).stream().findFirst();
  }

  /** 지원 내용·조건 줄을 한 번에 읽어 붙여요 (혜택 수만큼 질의하지 않게). */
  private List<Row> withLines(List<Row> rows) {
    if (rows.isEmpty()) return rows;
    UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
    Map<UUID, List<Support>> supports = new LinkedHashMap<>();
    jdbc.sql(
            "select * from benefit.benefit_supports where benefit_id = any(?) order by benefit_id,"
                + " ordinal")
        .param(ids)
        .query(
            rs -> {
              supports
                  .computeIfAbsent(rs.getObject("benefit_id", UUID.class), k -> new ArrayList<>())
                  .add(
                      new Support(
                          rs.getString("kind"),
                          (Integer) rs.getObject("amount_max"),
                          rs.getString("amount_per"),
                          (Integer) rs.getObject("months"),
                          rs.getBigDecimal("rate_pct"),
                          rs.getString("raw_text"),
                          rs.getString("evidence")));
            });
    Map<UUID, List<Condition>> conditions = new LinkedHashMap<>();
    jdbc.sql(
            "select * from benefit.benefit_conditions where benefit_id = any(?) order by"
                + " benefit_id, ordinal")
        .param(ids)
        .query(
            rs -> {
              conditions
                  .computeIfAbsent(rs.getObject("benefit_id", UUID.class), k -> new ArrayList<>())
                  .add(
                      new Condition(
                          rs.getString("type"),
                          rs.getString("value"),
                          rs.getInt("grp"),
                          rs.getBoolean("known"),
                          rs.getString("raw_text"),
                          rs.getString("evidence"),
                          rs.getDouble("confidence"),
                          rs.getString("origin")));
            });
    return rows.stream()
        .map(
            r ->
                new Row(
                    r.id(),
                    r.sourceCode(),
                    r.field(),
                    r.category(),
                    r.title(),
                    r.agency(),
                    r.regionScope(),
                    r.regionNames(),
                    r.regionCodes(),
                    r.categories(),
                    r.detailUrl(),
                    r.applyUrl(),
                    r.summary(),
                    r.applyStart(),
                    r.applyEnd(),
                    r.applyAlways(),
                    r.businessStart(),
                    r.businessEnd(),
                    r.scheduleText(),
                    r.howToApply(),
                    r.documents(),
                    r.contact(),
                    r.status(),
                    r.needsReview(),
                    supports.getOrDefault(r.id(), List.of()),
                    conditions.getOrDefault(r.id(), List.of())))
        .toList();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("source_code"),
        rs.getString("field"),
        rs.getString("category"),
        rs.getString("title"),
        rs.getString("agency"),
        rs.getString("region_scope"),
        strings(rs.getArray("region_names")),
        strings(rs.getArray("region_codes")),
        strings(rs.getArray("categories")),
        rs.getString("detail_url"),
        rs.getString("apply_url"),
        rs.getString("summary"),
        day(rs, "apply_start"),
        day(rs, "apply_end"),
        rs.getBoolean("apply_always"),
        day(rs, "business_start"),
        day(rs, "business_end"),
        rs.getString("schedule_text"),
        rs.getString("how_to_apply"),
        strings(rs.getArray("documents")),
        rs.getString("contact"),
        rs.getString("status"),
        rs.getBoolean("needs_review"),
        List.of(),
        List.of());
  }

  private static LocalDate day(ResultSet rs, String column) throws SQLException {
    Date d = rs.getDate(column);
    return d == null ? null : d.toLocalDate();
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
