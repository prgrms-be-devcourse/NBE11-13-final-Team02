package kr.mujuthaek.api.notice.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.notice.domain.CompletedNotice;
import kr.mujuthaek.api.notice.domain.NoticeStatus;
import kr.mujuthaek.api.notice.domain.ReviewStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 공고 쓰기: notices(목록 값·현재 버전), notice_versions(공개 버전), notice_posts(원본 글). 상세 내용은 JSON 글자로 주고받아요.
 */
@Repository
public class NoticeRepository {

  private final JdbcClient jdbc;

  public NoticeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 원본 글을 처리하는 트랜잭션을 줄 세워요(트랜잭션이 끝나면 풀림). */
  public void lockRawPost(UUID rawPostId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:id as text), 0))")
        .param("id", rawPostId)
        .query((rs, i) -> 1)
        .list();
  }

  /** 원본 글(원래 공고 또는 반영된 정정 공고)이 속한 공고. */
  public Optional<UUID> findNoticeIdByPost(UUID rawPostId) {
    return jdbc.sql("select notice_id from notice.notice_posts where raw_post_id = :id")
        .param("id", rawPostId)
        .query(UUID.class)
        .optional();
  }

  /**
   * 이 원본 글로 반영한 가장 높은 원본 버전과 그 검수 상태. 반영한 적이 없으면 빈 값. 공개 버전만 보면, 새 버전을 만들지 않은 결과(내용 같음·정정 유지) 뒤에 늦게
   * 온 옛 원본 버전이 새것으로 보여 반영돼요(v1 → 같은 내용 v3 → 다른 내용 v2). 처리 기록도 함께 봐요. 같은 원본 버전이면 승인본이 있는지가 중요해서(승인본을
   * 자동 결과로 덮지 않게) 승인을 앞에 둬요.
   */
  public Optional<SourceVersion> latestFromPost(UUID rawPostId) {
    return jdbc.sql(
            "select raw_version, review_status from ("
                + " select raw_version, review_status, created_at as at from notice.notice_versions"
                + " where raw_post_id = :rawPostId"
                + " union all select raw_version, review_status, received_at from notice.extraction_results"
                + " where raw_post_id = :rawPostId and outcome in ('NO_CHANGE', 'KEPT_CORRECTION',"
                + " 'KEPT_APPROVED', 'APPROVAL_RECORDED')"
                + ") seen order by raw_version desc, (review_status = 'APPROVED') desc, at desc limit 1")
        .param("rawPostId", rawPostId)
        .query(
            (rs, i) ->
                new SourceVersion(
                    rs.getInt("raw_version"), ReviewStatus.valueOf(rs.getString("review_status"))))
        .optional();
  }

  /** 원래 공고를 가리키는 정정 공고가 반영된 공고인지. */
  public boolean hasCorrection(UUID noticeId) {
    return jdbc.sql(
            "select exists (select 1 from notice.notice_posts where notice_id = :id"
                + " and kind = 'CORRECTION' and supersedes_post_id is not null)")
        .param("id", noticeId)
        .query(Boolean.class)
        .single();
  }

  /**
   * 이 공고에 반영한 정정 공고 중, 받은 글(rawPostId)이 아닌 것의 가장 늦은 게시일. 없거나 게시일을 모르면 빈 값. 늦게 도착한 더 이른 정정이 최신 정정을
   * 덮어쓰지 않게 비교해요.
   */
  public Optional<LocalDate> latestOtherCorrectionPostedOn(UUID noticeId, UUID rawPostId) {
    return jdbc.sql(
            "select max(posted_on) as posted_on from notice.notice_posts where notice_id = :id"
                + " and kind = 'CORRECTION' and raw_post_id <> :rawPostId")
        .param("id", noticeId)
        .param("rawPostId", rawPostId)
        .query((rs, i) -> Optional.ofNullable(rs.getObject("posted_on", LocalDate.class)))
        .single();
  }

  /** 원본 글을 공고에 이어요 (이미 있으면 게시일·시각만 갱신). postedOn은 원본 글 게시일 (모르면 null). */
  public void upsertPost(
      UUID rawPostId,
      UUID noticeId,
      String kind,
      UUID supersedesPostId,
      LocalDate postedOn,
      Instant now) {
    jdbc.sql(
            "insert into notice.notice_posts (raw_post_id, notice_id, kind, supersedes_post_id,"
                + " posted_on, created_at, updated_at) values (:rawPostId, :noticeId, :kind,"
                + " :supersedes, :postedOn, :now, :now)"
                + " on conflict (raw_post_id) do update set"
                + " posted_on = coalesce(excluded.posted_on, notice.notice_posts.posted_on),"
                + " updated_at = :now")
        .param("rawPostId", rawPostId)
        .param("noticeId", noticeId)
        .param("kind", kind)
        .param("supersedes", supersedesPostId)
        .param("postedOn", postedOn)
        .param("now", utc(now))
        .update();
  }

  /** 공고의 현재 공개 버전을 잠그고 읽어요 (같은 공고의 버전 갱신을 줄 세워요). */
  public Optional<CurrentVersion> lockCurrent(UUID noticeId) {
    return jdbc.sql(
            "select n.id, n.current_version, n.status, v.raw_version, v.review_status, v.content_hash,"
                + " (v.payload -> 'notice')::text as snapshot, v.conditions::text as conditions,"
                + " v.held_items::text as held_items,"
                + " v.application_windows::text as application_windows,"
                + " case when jsonb_typeof(v.payload -> 'judgment') = 'object'"
                + " then (v.payload -> 'judgment')::text end as judgment"
                + " from notice.notices n join notice.notice_versions v"
                + " on v.notice_id = n.id and v.version = n.current_version"
                + " where n.id = :noticeId for update of n")
        .param("noticeId", noticeId)
        .query(
            (rs, i) ->
                new CurrentVersion(
                    rs.getObject("id", UUID.class),
                    rs.getInt("current_version"),
                    NoticeStatus.valueOf(rs.getString("status")),
                    rs.getInt("raw_version"),
                    ReviewStatus.valueOf(rs.getString("review_status")),
                    rs.getString("content_hash"),
                    rs.getString("snapshot"),
                    rs.getString("conditions"),
                    rs.getString("held_items"),
                    rs.getString("application_windows"),
                    rs.getString("judgment")))
        .optional();
  }

  /** 새 공고를 공개해요. raw_post_id는 공고를 처음 만든 글로 두고(updateNotice는 바꾸지 않음), 정정은 notice_posts로 이어요. */
  public void insertNotice(UUID id, CompletedNotice n, int version, Listing listing, Instant now) {
    jdbc.sql(
            "insert into notice.notices (id, raw_post_id, field, title, provider, notice_type,"
                + " supply_type_text, region_code, apply_start, apply_end, posted_on, summary_text,"
                + " detail_url, current_version, current_raw_post_id, current_raw_version, reviewed,"
                + " status, next_status_check_at, needs_check, created_at, updated_at) values (:id,"
                + " :rawPostId, :field, :title, :provider, :noticeType, :supplyTypeText, :regionCode,"
                + " :applyStart, :applyEnd, :postedOn, :summary, :detailUrl, :version, :rawPostId,"
                + " :rawVersion, :reviewed, :status, :nextCheck, :needsCheck, :now, :now)")
        .params(listValues(id, n, version, listing))
        .param("now", utc(now))
        .update();
  }

  /** 공고의 목록 값과 현재 버전을 새 버전으로 바꿔요. */
  public void updateNotice(UUID id, CompletedNotice n, int version, Listing listing, Instant now) {
    jdbc.sql(
            "update notice.notices set field = :field, title = :title, provider = :provider,"
                + " notice_type = :noticeType, supply_type_text = :supplyTypeText,"
                + " region_code = :regionCode, apply_start = :applyStart, apply_end = :applyEnd,"
                + " posted_on = :postedOn, summary_text = :summary, detail_url = :detailUrl,"
                + " current_version = :version, current_raw_post_id = :rawPostId,"
                + " current_raw_version = :rawVersion, reviewed = :reviewed, status = :status,"
                + " next_status_check_at = :nextCheck, needs_check = :needsCheck, updated_at = :now"
                + " where id = :id")
        .params(listValues(id, n, version, listing))
        .param("now", utc(now))
        .update();
  }

  /** 현재 공개 버전이 이 원본 글·원본 버전에서 왔으면 그 버전과 공고를 승인으로 올려요(내용이 같은 승인본, 새 버전 없음). 올렸으면 true. */
  public boolean markCurrentApproved(UUID noticeId, UUID rawPostId, int rawVersion, Instant now) {
    int updated =
        jdbc.sql(
                "update notice.notice_versions v set review_status = 'APPROVED'"
                    + " from notice.notices n where n.id = :id and v.notice_id = n.id"
                    + " and v.version = n.current_version and v.raw_post_id = :rawPostId"
                    + " and v.raw_version = :rawVersion")
            .param("id", noticeId)
            .param("rawPostId", rawPostId)
            .param("rawVersion", rawVersion)
            .update();
    if (updated > 0) {
      jdbc.sql("update notice.notices set reviewed = true, updated_at = :now where id = :id")
          .param("id", noticeId)
          .param("now", utc(now))
          .update();
    }
    return updated > 0;
  }

  /** 다시 계산할 시각이 지난 공고를 잠그고 읽어요. 다른 서버가 이미 잡은 공고는 건너뛰어요(skip locked). 일정은 현재 버전의 원문 일정(상세)에서 읽어요. */
  public List<StatusCheckRow> lockDueForStatusCheck(Instant now, int limit) {
    return jdbc.sql(
            "select n.id, n.status, (v.payload -> 'notice')::text as detail, v.review_status"
                + " from notice.notices n join notice.notice_versions v"
                + " on v.notice_id = n.id and v.version = n.current_version"
                + " where n.next_status_check_at <= :now"
                + " order by n.next_status_check_at limit :limit"
                + " for update of n skip locked")
        .param("now", utc(now))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new StatusCheckRow(
                    rs.getObject("id", UUID.class),
                    NoticeStatus.valueOf(rs.getString("status")),
                    rs.getString("detail"),
                    ReviewStatus.valueOf(rs.getString("review_status"))))
        .list();
  }

  public void updateStatus(UUID noticeId, NoticeStatus status, Instant nextCheckAt, Instant now) {
    jdbc.sql(
            "update notice.notices set status = :status, next_status_check_at = :nextCheck,"
                + " updated_at = :now where id = :id")
        .param("id", noticeId)
        .param("status", status.name())
        .param("nextCheck", nextCheckAt == null ? null : utc(nextCheckAt))
        .param("now", utc(now))
        .update();
  }

  public void insertVersion(VersionRow row, Instant now) {
    jdbc.sql(
            "insert into notice.notice_versions (notice_id, version, raw_post_id, raw_version,"
                + " source_event_id, review_status, payload, conditions, held_items,"
                + " application_windows, content_hash, changed_fields, created_at) values"
                + " (:noticeId, :version, :rawPostId, :rawVersion, :eventId, :reviewStatus,"
                + " cast(:payload as jsonb), cast(:conditions as jsonb), cast(:heldItems as jsonb),"
                + " cast(:windows as jsonb), :contentHash, cast(:changedFields as jsonb), :now)")
        .param("noticeId", row.noticeId())
        .param("version", row.version())
        .param("rawPostId", row.rawPostId())
        .param("rawVersion", row.rawVersion())
        .param("eventId", row.sourceEventId())
        .param("reviewStatus", row.reviewStatus().name())
        .param("payload", row.payloadJson())
        .param("conditions", row.conditionsJson())
        .param("heldItems", row.heldItemsJson())
        .param("windows", row.applicationWindowsJson())
        .param("contentHash", row.contentHash())
        .param("changedFields", row.changedFieldsJson())
        .param("now", utc(now))
        .update();
  }

  private static Map<String, Object> listValues(
      UUID id, CompletedNotice n, int version, Listing listing) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("id", id);
    params.put("rawPostId", n.rawPostId());
    params.put("field", n.field());
    params.put("title", n.title());
    params.put("provider", n.provider());
    params.put("noticeType", n.noticeType() == null ? null : n.noticeType().name());
    params.put("supplyTypeText", n.supplyTypeText());
    params.put("regionCode", n.regionCode());
    params.put("applyStart", n.applyStart());
    params.put("applyEnd", n.applyEnd());
    params.put("postedOn", n.postedOn());
    params.put("summary", n.summary());
    params.put("detailUrl", n.detailUrl());
    params.put("version", version);
    params.put("rawVersion", n.rawVersion());
    params.put("reviewed", n.approved());
    params.put("status", listing.status().name());
    params.put("nextCheck", listing.nextCheckAt() == null ? null : utc(listing.nextCheckAt()));
    params.put("needsCheck", listing.needsCheck());
    return params;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /**
   * 버전과 함께 바뀌는 목록 값.
   *
   * @param nextCheckAt 접수 상태를 다시 계산할 시각 (없으면 null)
   * @param needsCheck 원문 확인 전까지 보류한 항목이 있는지
   */
  public record Listing(NoticeStatus status, Instant nextCheckAt, boolean needsCheck) {}

  /** 접수 상태를 다시 계산할 공고. detailJson은 현재 버전의 상세(원문 일정·보류 항목). */
  public record StatusCheckRow(
      UUID noticeId, NoticeStatus status, String detailJson, ReviewStatus reviewStatus) {}

  /** 공고의 현재 공개 버전 (새 결과와 비교할 때 써요). */
  public record CurrentVersion(
      UUID noticeId,
      int version,
      NoticeStatus status,
      int rawVersion,
      ReviewStatus reviewStatus,
      String contentHash,
      String snapshotJson,
      String conditionsJson,
      String heldItemsJson,
      String applicationWindowsJson,
      String judgmentJson) {}

  /** 한 원본 글에서 마지막으로 반영한 버전. */
  public record SourceVersion(int rawVersion, ReviewStatus reviewStatus) {}

  /** notice_versions 한 줄. JSON 칸은 글자로. */
  public record VersionRow(
      UUID noticeId,
      int version,
      UUID rawPostId,
      int rawVersion,
      UUID sourceEventId,
      ReviewStatus reviewStatus,
      String payloadJson,
      String conditionsJson,
      String heldItemsJson,
      String applicationWindowsJson,
      String contentHash,
      String changedFieldsJson) {}
}
