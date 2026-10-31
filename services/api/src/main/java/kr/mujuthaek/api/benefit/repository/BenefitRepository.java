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

  /** 저장된 혜택의 ID, 반영한 수집 버전, 범위 안(보이는 혜택)인지. */
  public record Saved(UUID id, int version, boolean relevant) {}

  /** 다른 모듈(알림)에 주는 혜택 요약 한 줄. */
  public record SummaryRow(
      UUID id,
      String field,
      int version,
      String title,
      String agency,
      String status,
      LocalDate applyStart,
      LocalDate applyEnd,
      boolean applyAlways,
      String regionScope,
      List<String> regionCodes,
      String detailUrl) {}

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
    return jdbc.sql(
            "select id, source_version, relevant from benefit.benefits where raw_policy_id = ?")
        .param(rawPolicyId)
        .query(
            (rs, i) ->
                new Saved(
                    rs.getObject("id", UUID.class),
                    rs.getInt("source_version"),
                    rs.getBoolean("relevant")))
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
    return openBenefits(regionCodes, null).rows();
  }

  /**
   * 판정 후보와, 그중 나이 조건만으로 지원 불가가 확실한 혜택 ID.
   *
   * @param rows 판정 후보 (마감일 순). 나이로 걸러진 혜택은 지원 내용·조건을 읽지 않아 비어 있어요
   * @param ruledOutByAge 나이로 걸러진 혜택 ID
   */
  public record Open(List<Row> rows, java.util.Set<UUID> ruledOutByAge) {}

  /**
   * 판정 후보를 읽으면서 나이 조건만으로 지원 불가가 확실한 혜택을 DB에서 골라요. 그 혜택은 지원 내용·조건을 읽지 않아요(요청마다 열린 혜택 수천 개의 조건을 다 읽고
   * 판정하던 것을 줄여요).
   *
   * <p>BenefitJudge와 같은 규칙만 써요: 안내 조건(EXCLUSIVE·PRIORITY)을 뺀 어느 한 그룹의 조건이 모두 「값을 아는(known) 나이 조건이고
   * 가장 어린·가장 많은 나이 모두 범위 밖」이면 그 그룹이 불충족이라 혜택 전체가 지원 불가예요 (Bands.ageWithin, min·max는 숫자일 때만 정수로).
   *
   * @param ages 지원자 만 나이 [가장 어린, 가장 많은] (Bands.ages). null이면 거르지 않아요
   */
  public Open openBenefits(Collection<String> regionCodes, int[] ages) {
    String[] regions = regionCodes.toArray(String[]::new);
    java.util.Set<UUID> ruledOut = new java.util.HashSet<>();
    List<Row> rows =
        jdbc.sql(
                "select b.*, "
                    + AGE_RULED_OUT
                    + " as ruled_out from benefit.benefits b where b.field = 'BENEFIT'"
                    + " and b.status in ('UPCOMING', 'OPEN') and (cardinality(cast(:regions as"
                    + " text[])) = 0 or b.region_scope = 'NATIONWIDE' or exists (select 1 from"
                    + " unnest(b.region_codes) r, unnest(cast(:regions as text[])) f where r like f"
                    + " || '%' or f like r || '%')) order by b.apply_end nulls last, b.id")
            .param("regions", regions)
            .param("young", ages == null ? null : ages[0])
            .param("old", ages == null ? null : ages[1])
            .query(
                (rs, i) -> {
                  Row r = row(rs, i);
                  if (rs.getBoolean("ruled_out")) ruledOut.add(r.id());
                  return r;
                })
            .list();
    return new Open(withLines(rows, ruledOut), ruledOut);
  }

  /** 정수 값 (BenefitJudge.integer: 숫자일 때만 소수점 아래를 버린 정수, 아니면 없음) */
  private static String ageBound(String key) {
    return "(case when jsonb_typeof(c.value -> '%s') = 'number' then trunc((c.value ->> '%s')::numeric) end)"
        .formatted(key, key);
  }

  /** 나이 하나가 범위 안인지 (Bands.fits) */
  private static String fits(String age) {
    return "((%s is null or %s >= %s) and (%s is null or %s <= %s))"
        .formatted(ageBound("min"), age, ageBound("min"), ageBound("max"), age, ageBound("max"));
  }

  private static final String AGE_RULED_OUT =
      "(cast(:young as integer) is not null and exists (select 1 from benefit.benefit_conditions c"
          + " where c.benefit_id = b.id and c.type not in ('EXCLUSIVE', 'PRIORITY') group by c.grp"
          + " having bool_and(c.type = 'AGE' and c.known and not "
          + fits("cast(:young as integer)")
          + " and not "
          + fits("cast(:old as integer)")
          + ")))";

  public Optional<Row> find(UUID id) {
    List<Row> rows =
        jdbc.sql("select * from benefit.benefits where id = ? and status <> 'HIDDEN'")
            .param(id)
            .query(BenefitRepository::row)
            .list();
    return withLines(rows, java.util.Set.of()).stream().findFirst();
  }

  /** 혜택 하나의 요약 (숨김 포함). */
  /** 혜택 ID → 정책 원본 ID (rawPolicyId) */
  public Optional<UUID> rawPolicyId(UUID id) {
    return jdbc.sql("select raw_policy_id from benefit.benefits where id = ?")
        .param(id)
        .query(UUID.class)
        .optional();
  }

  public Optional<SummaryRow> summary(UUID id) {
    return jdbc.sql(SUMMARY + " where id = ?")
        .param(id)
        .query(BenefitRepository::summary)
        .optional();
  }

  /** 혜택 여러 개의 요약을 한 번에 (숨김 포함, 없는 ID는 빠져요). 관심 목록이 한 줄씩 묻지 않게요. */
  public List<SummaryRow> summaries(Collection<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.sql(SUMMARY + " where id = any(?)")
        .param(ids.toArray(UUID[]::new))
        .query(BenefitRepository::summary)
        .list();
  }

  /** 수집 정책 ID로 혜택 하나의 요약 (숨김 포함). */
  public Optional<SummaryRow> summaryByRawPolicyId(UUID rawPolicyId) {
    return jdbc.sql(SUMMARY + " where raw_policy_id = ?")
        .param(rawPolicyId)
        .query(BenefitRepository::summary)
        .optional();
  }

  /** 마감일이 dates 중 하나이고 오늘 접수 중인 혜택 (숨김·상시·시작 전 제외). 상태 칸 대신 날짜로 골라요 (상태 재계산이 늦어도 같게) */
  public List<SummaryRow> openClosingOn(Collection<LocalDate> dates, LocalDate today) {
    if (dates.isEmpty()) return List.of();
    return jdbc.sql(
            SUMMARY
                + " where relevant and not apply_always and apply_end = any(?)"
                + " and (apply_start is null or apply_start <= ?) order by apply_end, id")
        .params(dates.stream().map(Date::valueOf).toArray(Date[]::new), Date.valueOf(today))
        .query(BenefitRepository::summary)
        .list();
  }

  private static final String SUMMARY =
      "select id, field, source_version, title, agency, status, apply_start, apply_end,"
          + " apply_always, region_scope, region_codes, detail_url from benefit.benefits";

  private static SummaryRow summary(ResultSet rs, int i) throws SQLException {
    return new SummaryRow(
        rs.getObject("id", UUID.class),
        rs.getString("field"),
        rs.getInt("source_version"),
        rs.getString("title"),
        rs.getString("agency"),
        rs.getString("status"),
        day(rs, "apply_start"),
        day(rs, "apply_end"),
        rs.getBoolean("apply_always"),
        rs.getString("region_scope"),
        strings(rs.getArray("region_codes")),
        rs.getString("detail_url"));
  }

  /** 지원 내용·조건 줄을 한 번에 읽어 붙여요 (혜택 수만큼 질의하지 않게). skip의 혜택은 읽지 않고 비워 둬요. */
  private List<Row> withLines(List<Row> rows, java.util.Set<UUID> skip) {
    if (rows.isEmpty()) return rows;
    UUID[] ids = rows.stream().map(Row::id).filter(id -> !skip.contains(id)).toArray(UUID[]::new);
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
