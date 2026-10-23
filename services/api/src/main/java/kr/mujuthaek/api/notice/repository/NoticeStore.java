package kr.mujuthaek.api.notice.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.notice.domain.CompletedNotice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** notice.notices·notice_sources 읽기·쓰기 (JDBC). 상세 내용은 jsonb로 그대로 저장해요. */
@Repository
public class NoticeStore {

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public NoticeStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** 공고의 목록용 값과 현재 공개 버전. */
  public record NoticeRow(
      UUID id,
      String title,
      String provider,
      String noticeType,
      String regionCode,
      LocalDate applyStart,
      LocalDate applyEnd,
      String summaryText,
      int currentVersion,
      UUID currentRawPostId,
      Integer currentRawVersion) {}

  /** 이 원본(rawPostId)이 이미 붙은 공고. 처음 만든 원본이거나, 이전 버전·정정으로 붙은 원본이면 찾아요. */
  public Optional<UUID> noticeIdOf(UUID rawPostId) {
    return jdbc
        .queryForList(
            """
            select id from notice.notices where raw_post_id = ?
            union
            select notice_id from notice.notice_sources where raw_post_id = ?
            limit 1
            """,
            UUID.class,
            rawPostId,
            rawPostId)
        .stream()
        .findFirst();
  }

  public Optional<NoticeRow> find(UUID noticeId) {
    return jdbc
        .query(
            """
            select id, title, provider, notice_type, region_code, apply_start, apply_end, summary_text,
                   current_version, current_raw_post_id, current_raw_version
            from notice.notices where id = ? for update
            """,
            (rs, i) ->
                new NoticeRow(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getObject(6, LocalDate.class),
                    rs.getObject(7, LocalDate.class),
                    rs.getString(8),
                    rs.getInt(9),
                    rs.getObject(10, UUID.class),
                    (Integer) rs.getObject(11)),
            noticeId)
        .stream()
        .findFirst();
  }

  /** 아직 공개 전인 공고를 만들어요 (목록 값은 공개할 때 채워요). */
  public void insertNotice(UUID id, CompletedNotice n) {
    jdbc.update(
        "insert into notice.notices (id, raw_post_id, field, title) values (?, ?, ?, ?)",
        id,
        n.rawPostId(),
        n.field(),
        n.title());
  }

  /** 받은 원본 버전을 저장해요. 같은 원본 버전을 다시 받으면 내용을 바꾸고 버전 번호는 그대로예요. */
  public void upsertSource(UUID noticeId, UUID eventId, CompletedNotice n) {
    jdbc.update(
        """
        insert into notice.notice_sources (raw_post_id, raw_version, notice_id, kind, review_status, event_id, payload)
        values (?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (raw_post_id, raw_version) do update
          set kind = excluded.kind, review_status = excluded.review_status, event_id = excluded.event_id,
              payload = excluded.payload, received_at = now()
        """,
        n.rawPostId(),
        n.rawVersion(),
        noticeId,
        n.kind(),
        n.reviewStatus(),
        eventId,
        json.writeValueAsString(n.payload()));
  }

  /** 이 원본 버전을 공고의 현재 공개 버전으로 해요. version은 이 원본의 공고 버전 번호예요. */
  public void publish(UUID noticeId, CompletedNotice n, int version) {
    jdbc.update(
        "update notice.notice_sources set version = ? where raw_post_id = ? and raw_version = ?",
        version,
        n.rawPostId(),
        n.rawVersion());
    jdbc.update(
        """
        update notice.notices
        set field = ?, title = ?, provider = ?, notice_type = ?, supply_type_text = ?, region_code = ?,
            apply_start = ?, apply_end = ?, posted_on = ?, summary_text = ?, detail_url = ?,
            current_version = ?, current_raw_post_id = ?, current_raw_version = ?, reviewed = ?, updated_at = now()
        where id = ?
        """,
        n.field(),
        n.title(),
        n.provider(),
        n.noticeType() == null ? null : n.noticeType().name(),
        n.supplyTypeText(),
        n.regionCode(),
        date(n.applyStart()),
        date(n.applyEnd()),
        date(n.postedOn()),
        n.summary(),
        n.detailUrl(),
        version,
        n.rawPostId(),
        n.rawVersion(),
        n.approved(),
        noticeId);
  }

  private static Date date(LocalDate value) {
    return value == null ? null : Date.valueOf(value);
  }
}
