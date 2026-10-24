package kr.mujuthaek.api.notice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 공고 제보 (notice.notice_reports). */
@Repository
public class NoticeReportRepository {

  private static final String COLUMNS =
      "r.id, r.notice_id, n.title, r.reporter_id, r.field, r.current_value, r.reported_value,"
          + " r.note, r.screen, r.status, r.result, r.reply, r.handled_by, r.created_at,"
          + " r.started_at, r.handled_at, r.review_job_id, (select count(*) from notice.notice_reports g where"
          + " g.notice_id = r.notice_id and g.field = r.field and g.status <> 'DONE') as open_in_group";

  private final JdbcClient jdbc;

  public NoticeReportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 공개된 공고의 제목 (없거나 숨김·공개 전이면 비어 있음). */
  public Optional<String> publishedTitle(UUID noticeId) {
    return jdbc.sql(
            "select title from notice.notices where id = :id and current_version > 0 and not"
                + " hidden")
        .param("id", noticeId)
        .query(String.class)
        .optional();
  }

  /** 그 회원이 since 뒤에 낸 제보 수. */
  public int countSince(UUID reporterId, Instant since) {
    return jdbc.sql(
            "select count(*) from notice.notice_reports where reporter_id = :r and created_at >="
                + " :since")
        .param("r", reporterId)
        .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
        .query(Integer.class)
        .single();
  }

  /** 넣어요. 같은 회원·공고·칸의 처리 전 제보가 있으면 넣지 않고 false. */
  public boolean insert(Row row) {
    return jdbc.sql(
                "insert into notice.notice_reports (id, notice_id, reporter_id, field,"
                    + " current_value, reported_value, note, screen, created_at) values (:id,"
                    + " :notice, :reporter, :field, :current, :reported, :note, :screen, :now) on"
                    + " conflict do nothing")
            .param("id", row.id())
            .param("notice", row.noticeId())
            .param("reporter", row.reporterId())
            .param("field", row.field())
            .param("current", row.currentValue())
            .param("reported", row.reportedValue())
            .param("note", row.note())
            .param("screen", row.screen())
            .param("now", OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC))
            .update()
        == 1;
  }

  public List<Report> list(String status, int limit) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from notice.notice_reports r join notice.notices n on n.id = r.notice_id"
                + " where (cast(:status as text) is null or r.status = :status)"
                + " order by r.created_at desc limit :limit")
        .param("status", status)
        .param("limit", limit)
        .query(NoticeReportRepository::report)
        .list();
  }

  public Optional<Report> find(UUID id) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from notice.notice_reports r join notice.notices n on n.id = r.notice_id"
                + " where r.id = :id")
        .param("id", id)
        .query(NoticeReportRepository::report)
        .optional();
  }

  /** 같은 공고·칸의 다른 제보 (설명만, 오래된 것부터). */
  public List<Report> sameGroup(UUID noticeId, String field, UUID except) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from notice.notice_reports r join notice.notices n on n.id = r.notice_id"
                + " where r.notice_id = :notice and r.field = :field and r.id <> :except"
                + " order by r.created_at limit 50")
        .param("notice", noticeId)
        .param("field", field)
        .param("except", except)
        .query(NoticeReportRepository::report)
        .list();
  }

  /** 상태별 건수: [NEW, DOING, DONE]. */
  public int[] counts() {
    int[] out = new int[3];
    jdbc.sql("select status, count(*) from notice.notice_reports group by status")
        .query(
            (rs, i) -> {
              int index =
                  switch (rs.getString(1)) {
                    case "NEW" -> 0;
                    case "DOING" -> 1;
                    default -> 2;
                  };
              out[index] = rs.getInt(2);
              return null;
            })
        .list();
    return out;
  }

  /** 처리 시작 (NEW → DOING). 바꾼 건수. */
  public int start(UUID id, String admin, Instant now) {
    return jdbc.sql(
            "update notice.notice_reports set status = 'DOING', started_at = :now, handled_by ="
                + " :admin where id = :id and status = 'NEW'")
        .param("id", id)
        .param("admin", admin)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .update();
  }

  /**
   * 처리 끝 (DONE). ids 중 아직 끝나지 않은 것만 바꾸고, 바꾼 제보를 돌려줘요(답장 이벤트용).
   *
   * @param result FIXED · KEPT
   */
  public List<Report> finish(
      List<UUID> ids, String result, String reply, String admin, Instant now) {
    if (ids.isEmpty()) {
      return List.of();
    }
    List<UUID> changed =
        jdbc.sql(
                "update notice.notice_reports set status = 'DONE', result = :result, reply ="
                    + " :reply, handled_by = :admin, handled_at = :now, started_at ="
                    + " coalesce(started_at, :now) where id in (:ids) and status <> 'DONE'"
                    + " returning id")
            .param("ids", ids)
            .param("result", result)
            .param("reply", reply)
            .param("admin", admin)
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .query(UUID.class)
            .list();
    return changed.stream().map(this::find).flatMap(Optional::stream).toList();
  }

  /** 공고의 지금 원본 글 ID (공개된 공고만). */
  public Optional<UUID> currentRawPost(UUID noticeId) {
    return jdbc.sql(
            "select current_raw_post_id from notice.notices where id = :id and current_version > 0")
        .param("id", noticeId)
        .query(UUID.class)
        .optional();
  }

  /** 다시 연 검수를 제보들에 잇고, 새 제보면 처리 중으로. */
  public void linkReview(List<UUID> ids, UUID reviewJobId, String admin, Instant now) {
    jdbc.sql(
            "update notice.notice_reports set review_job_id = :job, status = case when status ="
                + " 'NEW' then 'DOING' else status end, started_at = coalesce(started_at, :now),"
                + " handled_by = coalesce(handled_by, :admin) where id in (:ids) and status <>"
                + " 'DONE'")
        .param("ids", ids)
        .param("job", reviewJobId)
        .param("admin", admin)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .update();
  }

  /** 탈퇴한 회원의 제보를 지워요. 지운 건수. */
  public int deleteByReporter(UUID reporterId) {
    return jdbc.sql("delete from notice.notice_reports where reporter_id = :r")
        .param("r", reporterId)
        .update();
  }

  private static Report report(ResultSet rs, int i) throws SQLException {
    return new Report(
        rs.getObject("id", UUID.class),
        rs.getObject("notice_id", UUID.class),
        rs.getString("title"),
        rs.getObject("reporter_id", UUID.class),
        rs.getString("field"),
        rs.getString("current_value"),
        rs.getString("reported_value"),
        rs.getString("note"),
        rs.getString("screen"),
        rs.getString("status"),
        rs.getString("result"),
        rs.getString("reply"),
        rs.getString("handled_by"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("started_at", OffsetDateTime.class),
        rs.getObject("handled_at", OffsetDateTime.class),
        rs.getObject("review_job_id", UUID.class),
        rs.getInt("open_in_group"));
  }

  /** 넣을 제보. */
  public record Row(
      UUID id,
      UUID noticeId,
      UUID reporterId,
      String field,
      String currentValue,
      String reportedValue,
      String note,
      String screen,
      Instant createdAt) {}

  /**
   * 저장된 제보.
   *
   * @param openInGroup 같은 공고·칸의 처리 전 제보 수 (이 제보 포함)
   */
  public record Report(
      UUID id,
      UUID noticeId,
      String noticeTitle,
      UUID reporterId,
      String field,
      String currentValue,
      String reportedValue,
      String note,
      String screen,
      String status,
      String result,
      String reply,
      String handledBy,
      OffsetDateTime createdAt,
      OffsetDateTime startedAt,
      OffsetDateTime handledAt,
      UUID reviewJobId,
      int openInGroup) {}
}
