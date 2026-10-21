package kr.mujuthaek.api.notice.service;

import static kr.mujuthaek.api.notice.NoticeFixtures.correction;
import static kr.mujuthaek.api.notice.NoticeFixtures.followUp;
import static kr.mujuthaek.api.notice.NoticeFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.notice.NoticeFixtures;
import kr.mujuthaek.api.notice.repository.NoticeStore;
import kr.mujuthaek.api.notice.service.NoticeIngestService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

class NoticeIngestServiceTest {

  private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());
  private final NoticeIngestService service =
      new NoticeIngestService(new NoticeStore(jdbc, NoticeFixtures.JSON));

  @Test
  void firstResultCreatesAndPublishesTheNotice() {
    UUID raw = UUID.randomUUID();

    assertThat(service.accept(UUID.randomUUID(), payload(raw, 1))).isEqualTo(Outcome.PUBLISHED);

    Map<String, Object> row = notice(raw);
    assertThat(row)
        .containsEntry("title", "가상주거 청년 매입임대 입주자 모집 공고")
        .containsEntry("notice_type", "PURCHASE_RENTAL")
        .containsEntry("supply_type_text", "매입임대")
        .containsEntry("region_code", "11110")
        .containsEntry("current_version", 1)
        .containsEntry("detail_url", "https://example.org/notices/housing-1");
    assertThat(row.get("apply_end").toString()).isEqualTo("2026-10-31");
    assertThat(row.get("posted_on").toString()).isEqualTo("2026-10-01");
  }

  @Test
  void correctionBecomesTheNextVersionOfTheOriginal() {
    UUID original = UUID.randomUUID();
    UUID fix = UUID.randomUUID();
    service.accept(UUID.randomUUID(), payload(original, 1));
    ObjectNode corrected = correction(fix, original);
    ((ObjectNode) corrected.get("schedule")).put("applyEndOn", "2026-11-07");

    assertThat(service.accept(UUID.randomUUID(), corrected)).isEqualTo(Outcome.NEW_VERSION);

    Map<String, Object> row = notice(original);
    assertThat(row).containsEntry("current_version", 2);
    assertThat(row.get("apply_end").toString()).isEqualTo("2026-11-07");
    assertThat(row.get("current_raw_post_id")).isEqualTo(fix);
    assertThat(countNotices(original, fix)).isEqualTo(1);
  }

  @Test
  void inReviewKeepsThePublishedVersion() {
    UUID raw = UUID.randomUUID();
    service.accept(UUID.randomUUID(), payload(raw, 1));
    ObjectNode next = payload(raw, 2);
    next.put("reviewStatus", "IN_REVIEW");
    next.put("title", "검수 중인 새 제목");

    assertThat(service.accept(UUID.randomUUID(), next)).isEqualTo(Outcome.STORED_ONLY);
    assertThat(notice(raw))
        .containsEntry("current_version", 1)
        .containsEntry("title", "가상주거 청년 매입임대 입주자 모집 공고");
  }

  @Test
  void firstResultInReviewIsNotPublishedYet() {
    UUID raw = UUID.randomUUID();
    ObjectNode p = payload(raw, 1);
    p.put("reviewStatus", "IN_REVIEW");

    assertThat(service.accept(UUID.randomUUID(), p)).isEqualTo(Outcome.STORED_ONLY);
    assertThat(notice(raw)).containsEntry("current_version", 0);
  }

  @Test
  void approvedResendReplacesTheSameVersionAndMarksItReviewed() {
    UUID raw = UUID.randomUUID();
    service.accept(UUID.randomUUID(), payload(raw, 1));
    ObjectNode approved = payload(raw, 1);
    approved.put("reviewStatus", "APPROVED");
    approved.put("summary", "검수자가 고친 요약");

    assertThat(service.accept(UUID.randomUUID(), approved)).isEqualTo(Outcome.REFRESHED);
    assertThat(notice(raw))
        .containsEntry("current_version", 1)
        .containsEntry("reviewed", true)
        .containsEntry("summary_text", "검수자가 고친 요약");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notice.notice_sources where raw_post_id = ?",
                Integer.class,
                raw))
        .isEqualTo(1);
  }

  @Test
  void anOlderVersionArrivingLateIsOnlyStored() {
    UUID raw = UUID.randomUUID();
    service.accept(UUID.randomUUID(), payload(raw, 2));

    assertThat(service.accept(UUID.randomUUID(), payload(raw, 1))).isEqualTo(Outcome.STORED_ONLY);
    assertThat(notice(raw)).containsEntry("current_raw_version", 2);
  }

  @Test
  void followUpIsAttachedButDoesNotChangeTheNotice() {
    UUID original = UUID.randomUUID();
    service.accept(UUID.randomUUID(), payload(original, 1));

    assertThat(service.accept(UUID.randomUUID(), followUp(UUID.randomUUID(), original)))
        .isEqualTo(Outcome.STORED_ONLY);
    assertThat(notice(original)).containsEntry("current_version", 1);
  }

  @Test
  void policyResultsAreNotNotices() {
    UUID raw = UUID.randomUUID();
    ObjectNode p = payload(raw, 1);
    p.put("field", "POLICY");

    assertThat(service.accept(UUID.randomUUID(), p)).isEqualTo(Outcome.IGNORED_FIELD);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notice.notices where raw_post_id = ?", Integer.class, raw))
        .isZero();
  }

  private Map<String, Object> notice(UUID rawPostId) {
    return jdbc.queryForMap("select * from notice.notices where raw_post_id = ?", rawPostId);
  }

  private int countNotices(UUID a, UUID b) {
    return jdbc.queryForObject(
        "select count(*) from notice.notices where raw_post_id in (?, ?)", Integer.class, a, b);
  }
}
