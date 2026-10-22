package kr.mujuthaek.api.notice.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.common.pagination.CursorKey;
import kr.mujuthaek.api.notice.domain.NoticeStatus;
import kr.mujuthaek.api.notice.dto.request.NoticeListFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 공개된(현재 버전이 있고 숨기지 않은) 공고 조회. */
@Repository
public class NoticeQueries {

  /** 마감일 없는 공고는 마감 임박순 맨 뒤, 공고일 없는 공고는 최신순 맨 뒤. */
  static final LocalDate NO_END = LocalDate.of(9999, 12, 31);

  static final LocalDate NO_POSTED = LocalDate.of(1, 1, 1);

  private final NamedParameterJdbcTemplate jdbc;

  public NoticeQueries(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  public record ListRow(
      UUID id,
      String field,
      String title,
      String provider,
      String noticeType,
      String regionCode,
      LocalDate applyStart,
      LocalDate applyEnd,
      LocalDate postedOn) {}

  public List<ListRow> list(NoticeListFilter f, LocalDate today, CursorKey after, int fetchSize) {
    var params =
        new MapSqlParameterSource()
            .addValue("field", f.field())
            .addValue("today", Date.valueOf(today))
            .addValue("statuses", f.statuses())
            .addValue("limit", fetchSize);
    StringBuilder sql =
        new StringBuilder(
            """
            select id, field, title, provider, notice_type, region_code, apply_start, apply_end, posted_on
            from notice.notices n
            where current_version > 0 and not hidden and field = :field
            """);
    sql.append(" and ")
        .append(NoticeStatus.sql("n.apply_start", "n.apply_end", "cast(:today as date)"))
        .append(" in (:statuses)");
    if (!f.regionCodes().isEmpty()) {
      sql.append(" and (false");
      if (!f.sigunguCodes().isEmpty()) {
        sql.append(" or region_code in (:sigungu)");
        params.addValue("sigungu", f.sigunguCodes());
      }
      if (!f.sidoCodes().isEmpty()) {
        sql.append(" or left(region_code, 2) in (:sido)");
        params.addValue("sido", f.sidoCodes());
      }
      sql.append(")");
    }
    if ("BUY".equals(f.tenure())) {
      sql.append(" and notice_type in (:buy)");
      params.addValue("buy", NoticeListFilter.BUY_TYPES);
    } else if ("RENT".equals(f.tenure())) {
      sql.append(" and notice_type is not null and notice_type not in (:buy)");
      params.addValue("buy", NoticeListFilter.BUY_TYPES);
    }
    if (!f.noticeTypes().isEmpty()) {
      sql.append(" and notice_type in (:types)");
      params.addValue("types", f.noticeTypes());
    }
    if (!f.providers().isEmpty()) {
      sql.append(" and provider in (:providers)");
      params.addValue("providers", f.providers());
    }
    if ("LATEST".equals(f.sort())) {
      String key = "coalesce(posted_on, date '" + NO_POSTED + "')";
      if (after != null) {
        sql.append(" and (").append(key).append(", id) < (cast(:afterValue as date), :afterId)");
      }
      sql.append(" order by ").append(key).append(" desc, id desc");
    } else {
      String key = "coalesce(apply_end, date '" + NO_END + "')";
      if (after != null) {
        sql.append(" and (").append(key).append(", id) > (cast(:afterValue as date), :afterId)");
      }
      sql.append(" order by ").append(key).append(" asc, id asc");
    }
    if (after != null) {
      params.addValue("afterValue", after.value()).addValue("afterId", after.id());
    }
    sql.append(" limit :limit");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, i) ->
            new ListRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getObject(7, LocalDate.class),
                rs.getObject(8, LocalDate.class),
                rs.getObject(9, LocalDate.class)));
  }

  /** 공개된 공고의 현재 버전 (목록 값 + 저장한 payload). 숨김·공개 전·없는 공고는 빈 값. */
  public record CurrentRow(
      ListRow row,
      String supplyTypeText,
      String detailUrl,
      int version,
      boolean reviewed,
      OffsetDateTime updatedAt,
      String payload) {}

  public Optional<CurrentRow> current(UUID noticeId) {
    return jdbc
        .query(
            """
            select n.id, n.field, n.title, n.provider, n.notice_type, n.region_code, n.apply_start,
                   n.apply_end, n.posted_on, n.supply_type_text, n.detail_url, n.current_version,
                   n.reviewed, n.updated_at, v.payload::text
            from notice.notices n
            join notice.notice_versions v on v.notice_id = n.id and v.version = n.current_version
            where n.id = :id and n.current_version > 0 and not n.hidden
            """,
            new MapSqlParameterSource("id", noticeId),
            (rs, i) ->
                new CurrentRow(
                    new ListRow(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class),
                        rs.getObject(9, LocalDate.class)),
                    rs.getString(10),
                    rs.getString(11),
                    rs.getInt(12),
                    rs.getBoolean(13),
                    rs.getObject(14, OffsetDateTime.class),
                    rs.getString(15)))
        .stream()
        .findFirst();
  }

  /** 공개 버전 하나 (정정 이력). 글 종류는 payload에서 읽어요. */
  public record VersionRow(
      int version, String reviewStatus, OffsetDateTime receivedAt, String payload) {}

  /** 공개된 버전들을 버전 순서로. 숨김·공개 전·없는 공고면 빈 목록. */
  public List<VersionRow> versions(UUID noticeId) {
    return jdbc.query(
        """
        select v.version, v.review_status, v.created_at, v.payload::text
        from notice.notice_versions v join notice.notices n on n.id = v.notice_id
        where v.notice_id = :id and n.current_version > 0 and not n.hidden
        order by v.version
        """,
        new MapSqlParameterSource("id", noticeId),
        (rs, i) ->
            new VersionRow(
                rs.getInt(1),
                rs.getString(2),
                rs.getObject(3, OffsetDateTime.class),
                rs.getString(4)));
  }

  /** 커서에 넣을 정렬 값 (목록의 정렬 식과 같아야 해요). */
  public static String sortValue(ListRow row, String sort) {
    if ("LATEST".equals(sort)) {
      return (row.postedOn() == null ? NO_POSTED : row.postedOn()).toString();
    }
    return (row.applyEnd() == null ? NO_END : row.applyEnd()).toString();
  }
}
