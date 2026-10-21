package kr.mujuthaek.api.area.repository;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import kr.mujuthaek.api.area.domain.AreaLoadReport;
import kr.mujuthaek.api.area.domain.AreaLoadRequest;
import kr.mujuthaek.api.area.domain.AreaSource;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.stereotype.Component;

/**
 * 원천 파일을 area.facilities / area.stores에 반영해요. JDBC만 써서 테스트에서는 {@code new}로 만들어요.
 *
 * <p>순서: 머리줄 확인 → 머리줄 이름 그대로 임시 테이블(stage_raw) → COPY → 변환 SQL(stage_norm) → 공통 검사 → 반영·삭제 → 실행 기록.
 * 데이터 변경은 트랜잭션 하나라 실패하면 이전 데이터가 그대로 남아요. 실행 기록은 실패해도 남아야 해서 커넥션을 직접 잡고 커밋·롤백해요 — 부르는 쪽에
 * {@code @Transactional}을 붙이지 마세요.
 *
 * <p>{@code @Repository}는 예외 변환 프록시(CGLIB)를 만들어 final 클래스에 붙일 수 없어서 {@code @Component}예요.
 */
@Component
public final class PostgresAreaBaseDataLoader {

  private static final String SQL_DIR = "area/load/";

  private final DataSource dataSource;
  private final Map<String, String> sqlCache = new ConcurrentHashMap<>();

  public PostgresAreaBaseDataLoader(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 원천 파일 내용을 반영해요. 파일 문제는 FAILED 결과로, DB 연결 문제는 예외로 알려요. */
  public AreaLoadReport load(AreaLoadRequest request, InputStream content) {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      UUID runId = insertRun(conn, request, "RUNNING");
      try {
        conn.setAutoCommit(false);
        AreaLoadReport report = loadInTransaction(conn, request, runId, content);
        finishRun(conn, report);
        conn.commit();
        return report;
      } catch (Exception e) {
        conn.rollback();
        conn.setAutoCommit(true);
        String message = describe(e);
        failRun(conn, runId, message);
        return AreaLoadReport.failed(runId, message);
      }
    } catch (SQLException e) {
      throw new AreaLoadException("적재 기록을 남기지 못했어요: " + e.getMessage(), e);
    }
  }

  private AreaLoadReport loadInTransaction(
      Connection conn, AreaLoadRequest req, UUID runId, InputStream content)
      throws SQLException, IOException {
    AreaSource source = req.source();
    lock(conn, source, req.scope());

    BufferedInputStream in = new BufferedInputStream(content, 1 << 16);
    List<String> header = CsvHeader.read(in, source.charset());
    List<String> missing =
        source.requiredColumns().stream().filter(c -> !header.contains(c)).toList();
    if (!missing.isEmpty()) {
      throw new AreaLoadException("파일에 꼭 있어야 하는 칸이 없어요: " + missing + " (파일의 칸: " + header + ")");
    }

    List<String> columns = safeColumnNames(header);
    try (Statement st = conn.createStatement()) {
      st.execute(createStageRaw(columns));
      st.execute(STAGE_NORM_DDL);
    }
    copyRows(conn, columns, source.charset(), new BlankLineSkippingInputStream(in));

    try (Statement st = conn.createStatement()) {
      st.execute(sql(source.mappingSql()));
      st.execute(sql("validate.sql"));
      // 임시 테이블은 자동 통계가 없어요. 이게 없으면 "파일에 없는 줄 지우기"가 줄 수의 제곱만큼 비교해요.
      st.execute(
          "create index on stage_norm (source_key) where skip_reason is null and reject_reason is null");
      st.execute("analyze stage_norm");
    }

    Counts c = count(conn);
    Date dataDate = Date.valueOf(req.dataDate());
    int[] iu;
    int deleted;
    if (source.target() == AreaSource.Target.FACILITIES) {
      iu =
          queryPair(
              conn, sql("apply_facilities.sql"), source.facilityKind(), source.name(), dataDate);
      deleted = update(conn, sql("delete_facilities.sql"), source.name());
      update(conn, sql("touch_facilities.sql"), dataDate, source.name(), dataDate);
    } else {
      // 상가는 시도 파일 하나가 적재 한 번이라, 지우기·기준일 맞추기를 그 시도(load_scope) 안에서만 해요.
      iu = queryPair(conn, sql("apply_stores.sql"), req.scope(), dataDate);
      deleted = update(conn, sql("delete_stores.sql"), req.scope());
      update(conn, sql("touch_stores.sql"), dataDate, req.scope(), dataDate);
    }
    int inserted = iu[0];
    int updated = iu[1];
    int unchanged = c.accepted - inserted - updated;
    return new AreaLoadReport(
        runId,
        AreaLoadReport.Status.SUCCEEDED,
        c.rowsIn,
        c.skipped,
        c.rejected,
        c.fixed,
        inserted,
        updated,
        unchanged,
        deleted,
        c.rejectReasons,
        null);
  }

  // ── 실행 기록 ────────────────────────────────────────────────────────────

  private static UUID insertRun(Connection conn, AreaLoadRequest r, String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into area.load_runs (source, load_scope, file_key, file_sha256, data_date, status, finished_at) "
                + "values (?, ?, ?, ?, ?, ?, case when ? = 'SKIPPED' then now() end) returning id")) {
      ps.setString(1, r.source().name());
      ps.setString(2, r.scope());
      ps.setString(3, r.fileKey());
      ps.setString(4, r.sha256());
      ps.setDate(5, Date.valueOf(r.dataDate()));
      ps.setString(6, status);
      ps.setString(7, status);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static void finishRun(Connection conn, AreaLoadReport r) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "update area.load_runs set status = 'SUCCEEDED', rows_in = ?, rows_skipped = ?, rows_rejected = ?, "
                + "rows_fixed = ?, rows_inserted = ?, rows_updated = ?, rows_unchanged = ?, rows_deleted = ?, "
                + "reject_reasons = ?::jsonb, finished_at = now() where id = ?")) {
      ps.setInt(1, r.rowsIn());
      ps.setInt(2, r.rowsSkipped());
      ps.setInt(3, r.rowsRejected());
      ps.setInt(4, r.rowsFixed());
      ps.setInt(5, r.rowsInserted());
      ps.setInt(6, r.rowsUpdated());
      ps.setInt(7, r.rowsUnchanged());
      ps.setInt(8, r.rowsDeleted());
      ps.setString(9, toJson(r.rejectReasons()));
      ps.setObject(10, r.runId());
      ps.executeUpdate();
    }
  }

  private static void failRun(Connection conn, UUID runId, String message) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "update area.load_runs set status = 'FAILED', error = ?, finished_at = now() where id = ?")) {
      ps.setString(1, message);
      ps.setObject(2, runId);
      ps.executeUpdate();
    }
  }

  // ── 단계별 도우미 ─────────────────────────────────────────────────────────

  /** 같은 원천·범위를 동시에 적재하지 않게 트랜잭션 잠금을 걸어요. */
  private static void lock(Connection conn, AreaSource source, String scope) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("select pg_advisory_xact_lock(hashtext(?))")) {
      ps.setString(1, "area.load:" + source.name() + ":" + scope);
      ps.execute();
    }
  }

  /**
   * 임시 테이블 칸 이름. 원천 칸 이름을 그대로 쓰되, PostgreSQL 이름 길이(63바이트)를 넘거나 비었거나 겹치면 c{번호}로 바꿔요. 변환 SQL이 쓰는 칸은
   * 모두 짧아서 그대로 남아요.
   */
  static List<String> safeColumnNames(List<String> header) {
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < header.size(); i++) {
      String name = header.get(i);
      boolean ok =
          !name.isBlank()
              && name.getBytes(StandardCharsets.UTF_8).length <= 60
              && !"line_no".equals(name);
      String chosen = ok && seen.add(name) ? name : "c" + (i + 1);
      seen.add(chosen);
      out.add(chosen);
    }
    return out;
  }

  private static String createStageRaw(List<String> columns) {
    StringBuilder ddl = new StringBuilder("create temp table stage_raw (line_no bigserial");
    for (String c : columns) {
      ddl.append(", ").append(quote(c)).append(" text");
    }
    return ddl.append(") on commit drop").toString();
  }

  private static void copyRows(
      Connection conn, List<String> columns, Charset charset, InputStream rows)
      throws SQLException, IOException {
    String encoding = charset.equals(StandardCharsets.UTF_8) ? "UTF8" : "UHC";
    String copy =
        "copy stage_raw ("
            + String.join(", ", columns.stream().map(PostgresAreaBaseDataLoader::quote).toList())
            + ") from stdin with (format csv, encoding '"
            + encoding
            + "')";
    new CopyManager(conn.unwrap(BaseConnection.class)).copyIn(copy, rows);
  }

  private record Counts(
      int rowsIn,
      int skipped,
      int rejected,
      int fixed,
      int accepted,
      Map<String, Integer> rejectReasons) {}

  private static Counts count(Connection conn) throws SQLException {
    int rowsIn;
    int skipped;
    int fixed;
    int accepted;
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select (select count(*) from stage_raw), "
                    + "count(*) filter (where skip_reason is not null), "
                    + "count(*) filter (where skip_reason is null and reject_reason is null and fixed), "
                    + "count(*) filter (where skip_reason is null and reject_reason is null) from stage_norm")) {
      rs.next();
      rowsIn = rs.getInt(1);
      skipped = rs.getInt(2);
      fixed = rs.getInt(3);
      accepted = rs.getInt(4);
    }
    Map<String, Integer> reasons = new LinkedHashMap<>();
    int rejected = 0;
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select reject_reason, count(*) from stage_norm where reject_reason is not null group by 1 order by 1")) {
      while (rs.next()) {
        reasons.put(rs.getString(1), rs.getInt(2));
        rejected += rs.getInt(2);
      }
    }
    return new Counts(rowsIn, skipped, rejected, fixed, accepted, reasons);
  }

  private static int[] queryPair(Connection conn, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new int[] {rs.getInt(1), rs.getInt(2)};
      }
    }
  }

  private static int update(Connection conn, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  private static void bind(PreparedStatement ps, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      ps.setObject(i + 1, params[i]);
    }
  }

  private String sql(String name) {
    return sqlCache.computeIfAbsent(
        name,
        n -> {
          try (InputStream in =
              PostgresAreaBaseDataLoader.class.getClassLoader().getResourceAsStream(SQL_DIR + n)) {
            if (in == null) {
              throw new IllegalStateException("SQL 파일이 없어요: " + SQL_DIR + n);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static String toJson(Map<String, Integer> map) {
    StringBuilder json = new StringBuilder("{");
    map.forEach(
        (k, v) -> {
          if (json.length() > 1) {
            json.append(',');
          }
          json.append('"').append(k.replace("\"", "")).append("\":").append(v);
        });
    return json.append('}').toString();
  }

  private static String describe(Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }

  private static final String STAGE_NORM_DDL =
      """
      create temp table stage_norm (
          line_no       bigint primary key,
          skip_reason   text,
          reject_reason text,
          fixed         boolean not null default false,
          source_key    text,
          name          text,
          sub_kind      text,
          address       text,
          lat_txt       text,
          lng_txt       text,
          lat           double precision,
          lng           double precision,
          extra         jsonb,
          large_code    text,
          large_name    text,
          middle_code   text,
          middle_name   text,
          small_code    text,
          small_name    text
      ) on commit drop""";
}
