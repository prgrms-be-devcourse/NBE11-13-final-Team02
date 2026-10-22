package kr.mujuthaek.api.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.common.event.NoticeClosed;
import kr.mujuthaek.api.common.event.NoticePublished;
import kr.mujuthaek.api.common.event.NoticeUpdated;
import kr.mujuthaek.api.notice.domain.IngestionOutcome;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse;
import kr.mujuthaek.api.notice.mapper.ExtractionEvent;
import kr.mujuthaek.api.notice.repository.ExtractionResultRepository;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import kr.mujuthaek.api.notice.service.internal.NoticeIngestionService;
import kr.mujuthaek.api.notice.service.internal.NoticeStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 실제 extraction 결과(서리풀 공고, PARTIALLY_PASSED)로 수신·저장·조회를 확인해요. AI 호출은 없어요. */
@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "notice.status-refresh.enabled=false" // 재계산은 테스트에서 직접 불러요
    })
@Import({TestcontainersConfiguration.class, NoticeIngestionIntegrationTest.FixedClock.class})
@RecordApplicationEvents
class NoticeIngestionIntegrationTest {

  /** 기본 2026-10-19 09:00 (한국). 서리풀 공고 접수는 10/26~10/30이에요. 테스트에서 시각을 옮길 수 있어요. */
  static final Instant START = Instant.parse("2026-10-19T00:00:00Z");

  static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

  static final class MovableClock extends Clock {
    private volatile Instant now = START;

    void set(String koreaDateTime) {
      now = LocalDateTime.parse(koreaDateTime).atZone(KOREA).toInstant();
    }

    @Override
    public ZoneId getZone() {
      return KOREA;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {
    @Bean
    @Primary
    MovableClock movableClock() {
      return new MovableClock();
    }
  }

  @Autowired NoticeIngestionService ingestion;
  @Autowired NoticeQueryService query;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired ApplicationEvents events;
  @Autowired MovableClock clock;
  @Autowired NoticeStatusService status;
  @MockitoSpyBean ExtractionResultRepository results;

  @BeforeEach
  void clean() {
    clock.now = START;
    jdbc.sql(
            "truncate notice.notice_versions, notice.notice_posts, notice.notice_follow_ups,"
                + " notice.notices, notice.extraction_results, common.processed_events")
        .update();
  }

  // ---------- 수신·공개 ----------

  @Test
  void 부분_게시_결과를_공개하고_NoticePublished를_보낸다() {
    assertThat(ingestion.apply(body(fixture()))).isEqualTo(IngestionOutcome.PUBLISHED);

    UUID id = onlyNotice();
    NoticeDetailResponse detail = query.detail(id);
    assertThat(detail.header().title())
        .isEqualTo(fixture().path("payload").path("title").asString());
    assertThat(detail.header().regionCode()).isEqualTo("11650");
    assertThat(detail.version()).isEqualTo(1);
    assertThat(events.stream(NoticePublished.class))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.noticeId()).isEqualTo(id);
              assertThat(e.regionCode()).isEqualTo("11650");
              assertThat(e.reprocess()).isFalse();
            });
  }

  @Test
  void 같은_이벤트를_두_번_받아도_공고와_버전은_하나다() {
    ObjectNode event = fixture();
    ingestion.apply(body(event));

    assertThat(ingestion.apply(body(event))).isEqualTo(IngestionOutcome.DUPLICATE);
    assertThat(count("notice.notices")).isEqualTo(1);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
    assertThat(count("notice.extraction_results")).isEqualTo(1);
    assertThat(events.stream(NoticePublished.class)).hasSize(1);
  }

  @Test
  void 검수_대기_결과는_공개하지_않고_기록만_남긴다() {
    assertThat(ingestion.apply(body(withStatus(fixture(), "IN_REVIEW"))))
        .isEqualTo(IngestionOutcome.HELD);

    assertThat(count("notice.notices")).isZero();
    assertThat(outcomes()).containsExactly("HELD:IN_REVIEW");
    assertThat(events.stream(NoticePublished.class)).isEmpty();
  }

  @Test
  void 같은_원본_버전이_검수_대기로_다시_와도_공개_중인_내용은_그대로다() {
    ingestion.apply(body(fixture()));
    ObjectNode inReview = withStatus(fixture(), "IN_REVIEW");
    inReview.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) inReview.path("payload")).put("summary", "검수 전 요약");

    assertThat(ingestion.apply(body(inReview))).isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
    assertThat(query.detail(onlyNotice()).summary().summaryText()).isNotEqualTo("검수 전 요약");
  }

  @Test
  void 상세가_없는_결과는_공개하지_않는다() {
    ObjectNode event = withStatus(fixture(), "IN_REVIEW");
    ((ObjectNode) event.path("payload")).remove("notice");

    assertThat(ingestion.apply(body(event))).isEqualTo(IngestionOutcome.HELD);
    assertThat(outcomes()).containsExactly("HELD:DETAIL_MISSING");
  }

  @Test
  void 내용이_같은_새_이벤트는_새_버전을_만들지_않는다() {
    ingestion.apply(body(fixture()));
    ObjectNode again = fixture();
    again.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) again.path("payload").path("notice").path("input"))
        .put("eventId", UUID.randomUUID().toString());

    assertThat(ingestion.apply(body(again))).isEqualTo(IngestionOutcome.NO_CHANGE);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
  }

  @Test
  void 근거_위치_같은_보이지_않는_부분만_바뀌면_새_버전을_만들지_않는다() {
    ingestion.apply(body(fixture()));
    ObjectNode next = withRawVersion(fixture(), 2); // 원본 버전 기록만 다르고 보이는 내용은 같음
    next.put("eventId", UUID.randomUUID().toString());

    assertThat(ingestion.apply(body(next))).isEqualTo(IngestionOutcome.NO_CHANGE);
    assertThat(outcomes()).contains("NO_CHANGE:NO_VISIBLE_CHANGE");
    assertThat(count("notice.notice_versions")).isEqualTo(1);
    assertThat(events.stream(NoticeUpdated.class)).isEmpty();
  }

  @Test
  void 내용이_바뀐_새_원본_버전은_새_버전으로_교체하고_NoticeUpdated를_보낸다() {
    ingestion.apply(body(fixture()));
    ObjectNode changed = withRawVersion(fixture(), 2);
    changed.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) changed.path("payload")).put("title", "바뀐 제목");
    ((ObjectNode) changed.path("payload").path("notice").path("notice")).put("title", "바뀐 제목");

    assertThat(ingestion.apply(body(changed))).isEqualTo(IngestionOutcome.NEW_VERSION);

    NoticeDetailResponse detail = query.detail(onlyNotice());
    assertThat(detail.header().title()).isEqualTo("바뀐 제목");
    assertThat(detail.version()).isEqualTo(2);
    assertThat(events.stream(NoticeUpdated.class))
        .singleElement()
        .satisfies(e -> assertThat(e.changedFields()).containsExactly("notice"));
  }

  @Test
  void 낮은_원본_버전은_STALE로_기록만_한다() {
    ObjectNode newer = withRawVersion(fixture(), 3);
    ingestion.apply(body(newer));
    ObjectNode older = withRawVersion(fixture(), 2);
    older.put("eventId", UUID.randomUUID().toString());

    assertThat(ingestion.apply(body(older))).isEqualTo(IngestionOutcome.STALE);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
  }

  @Test
  void 옛_원본_버전이_내용이_같은_최신_버전보다_늦게_와도_덮어쓰지_않는다() {
    ingestion.apply(body(fixture()));
    ObjectNode sameContent = withRawVersion(fixture(), 3);
    sameContent.put("eventId", UUID.randomUUID().toString());
    assertThat(ingestion.apply(body(sameContent))).isEqualTo(IngestionOutcome.NO_CHANGE);

    ObjectNode late = withRawVersion(fixture(), 2);
    late.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) late.path("payload")).put("title", "늦게 온 옛 버전");
    ((ObjectNode) late.path("payload").path("notice").path("notice")).put("title", "늦게 온 옛 버전");

    assertThat(ingestion.apply(body(late))).isEqualTo(IngestionOutcome.STALE);
    assertThat(query.detail(onlyNotice()).header().title()).isNotEqualTo("늦게 온 옛 버전");
  }

  @Test
  void 같은_원본_버전의_승인본이_있으면_자동_결과로_바꾸지_않는다() {
    ingestion.apply(body(withStatus(fixture(), "APPROVED")));
    ObjectNode auto = fixture();
    auto.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) auto.path("payload")).put("title", "자동 결과");
    ((ObjectNode) auto.path("payload").path("notice").path("notice")).put("title", "자동 결과");

    assertThat(ingestion.apply(body(auto))).isEqualTo(IngestionOutcome.KEPT_APPROVED);
    assertThat(query.detail(onlyNotice()).reviewed()).isTrue();
  }

  @Test
  void 내용이_같은_승인본을_받으면_승인을_기록해_뒤의_자동_결과가_덮어쓰지_않는다() {
    // 자동 통과(보류·검수 조건 없음) 결과와 같은 내용을 검수자가 승인한 경우: 해시가 같아 그냥 두면 「변화 없음」으로 끝나요
    ObjectNode auto = load("/contracts/examples/extraction.completed.json");
    assertThat(ingestion.apply(body(auto))).isEqualTo(IngestionOutcome.PUBLISHED);
    assertThat(query.detail(onlyNotice()).reviewed()).isFalse();

    ObjectNode approved = withStatus(auto.deepCopy(), "APPROVED");
    approved.put("eventId", UUID.randomUUID().toString());
    assertThat(ingestion.apply(body(approved))).isEqualTo(IngestionOutcome.APPROVAL_RECORDED);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
    assertThat(query.detail(onlyNotice()).reviewed()).isTrue();

    ObjectNode reprocessed = auto.deepCopy();
    reprocessed.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) reprocessed.path("payload")).put("title", "자동 결과");
    ((ObjectNode) reprocessed.path("payload").path("notice").path("notice")).put("title", "자동 결과");
    assertThat(ingestion.apply(body(reprocessed))).isEqualTo(IngestionOutcome.KEPT_APPROVED);
  }

  @Test
  void 공고가_아닌_분야는_기록만_남긴다() {
    ObjectNode policy = fixture();
    ((ObjectNode) policy.path("payload")).put("field", "POLICY");
    ((ObjectNode) policy.path("payload")).remove("notice");

    assertThat(ingestion.apply(body(policy))).isEqualTo(IngestionOutcome.UNSUPPORTED_FIELD);
    assertThat(count("notice.notices")).isZero();
    assertThat(outcomes()).containsExactly("UNSUPPORTED_FIELD:POLICY");
  }

  @Test
  void 계약에_맞지_않으면_아무것도_저장하지_않는다() {
    ObjectNode broken = fixture();
    ((ObjectNode) broken.path("payload")).put("reviewStatus", "DONE");

    assertThatThrownBy(() -> ingestion.apply(body(broken)))
        .isInstanceOf(ExtractionEvent.InvalidEventException.class);
    assertThatThrownBy(() -> ingestion.apply("not json"))
        .isInstanceOf(ExtractionEvent.InvalidEventException.class);
    assertThat(count("notice.extraction_results")).isZero();
  }

  @Test
  void 다른_형식_버전이나_상세와_보류_범위가_다른_이벤트는_받지_않는다() {
    ObjectNode future = fixture();
    future.put("schemaVersion", 999);
    ObjectNode conflict = fixture();
    ((ObjectNode) conflict.path("payload")).putArray("heldItems"); // 상세에는 보류 항목이 있음

    assertThatThrownBy(() -> ingestion.apply(body(future)))
        .isInstanceOf(ExtractionEvent.InvalidEventException.class)
        .hasMessageContaining("schemaVersion");
    assertThatThrownBy(() -> ingestion.apply(body(conflict)))
        .isInstanceOf(ExtractionEvent.InvalidEventException.class)
        .hasMessageContaining("heldItems");
    assertThat(count("notice.extraction_results")).isZero();
  }

  @Test
  void 원래_공고를_가리키지_않는_정정은_일반_공고로_공개하지_않고_보류한다() {
    ObjectNode correction = withStatus(correctionOf(fixture(), "2026-10-30"), "IN_REVIEW");
    ((ObjectNode) correction.path("payload").path("notice").path("sourceReferences").get(0))
        .putNull("supersedesPostId");

    assertThat(ingestion.apply(body(correction))).isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notices")).isZero();
    assertThat(outcomes()).containsExactly("HELD:UNLINKED_RELATED_NOTICE");
  }

  @Test
  void 저장_중_실패하면_처리_기록까지_되돌려져_다시_받으면_정상_처리된다() {
    ObjectNode event = fixture();
    doThrow(new IllegalStateException("저장 실패"))
        .when(results)
        .insert(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> ingestion.apply(body(event))).hasMessageContaining("저장 실패");
    assertThat(count("notice.extraction_results")).isZero();
    assertThat(count("notice.notices")).isZero();

    org.mockito.Mockito.reset(results);
    assertThat(ingestion.apply(body(event))).isEqualTo(IngestionOutcome.PUBLISHED);
  }

  // ---------- 접수 상태 ----------

  @Test
  void 날짜가_지나면_접수_상태를_바꾸고_마감되면_NoticeClosed를_보낸다() {
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();
    assertThat(statusOf(id)).isEqualTo("UPCOMING");

    clock.set("2026-10-25T23:59");
    assertThat(status.refreshDue(10)).isZero(); // 다시 계산할 시각(10/26 0시) 전

    clock.set("2026-10-26T00:05");
    assertThat(status.refreshDue(10)).isEqualTo(1);
    assertThat(statusOf(id)).isEqualTo("APPLYING");
    assertThat(events.stream(NoticeClosed.class)).isEmpty();

    clock.set("2026-10-30T16:59");
    assertThat(status.refreshDue(10)).isZero(); // 원문 마감 시각(10/30 17:00) 전은 접수 중

    clock.set("2026-10-30T17:01"); // 날짜만 보면 그날 자정까지 접수 중으로 남아요
    assertThat(status.refreshDue(10)).isEqualTo(1);
    assertThat(statusOf(id)).isEqualTo("CLOSED");
    assertThat(events.stream(NoticeClosed.class))
        .containsExactly(new NoticeClosed(id, LocalDate.of(2026, 10, 30)));

    clock.set("2026-12-31T00:00");
    assertThat(status.refreshDue(10)).isZero(); // 마감 뒤에는 다시 계산하지 않음
  }

  @Test
  void 재처리로_접수가_끝나도_마감_알림_이벤트는_보내지_않는다() {
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();
    clock.set("2026-10-31T00:05");
    ObjectNode reprocessed = correctionOf(fixture(), "2026-10-30");
    ((ObjectNode) reprocessed.path("payload")).put("reprocess", true);
    ((ObjectNode) reprocessed.path("payload").path("notice").path("input")).put("reprocess", true);
    events.clear();

    assertThat(ingestion.apply(body(reprocessed))).isEqualTo(IngestionOutcome.NEW_VERSION);
    assertThat(statusOf(id)).isEqualTo("CLOSED");
    assertThat(events.stream(NoticeClosed.class)).isEmpty();
  }

  @Test
  void 처음부터_마감인_공고는_NoticeClosed를_보내지_않는다() {
    clock.set("2026-11-15T10:00");
    ingestion.apply(body(fixture()));

    assertThat(statusOf(onlyNotice())).isEqualTo("CLOSED");
    assertThat(status.refreshDue(10)).isZero();
    assertThat(events.stream(NoticeClosed.class)).isEmpty();
  }

  @Test
  void 정정으로_접수가_끝나면_NoticeClosed를_보낸다() {
    clock.set("2026-10-27T10:00");
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();
    assertThat(statusOf(id)).isEqualTo("APPLYING");

    ObjectNode corrected = withRawVersion(fixture(), 2); // 마감일을 10/26로 앞당김
    corrected.put("eventId", UUID.randomUUID().toString());
    setApplicationEnd((ObjectNode) corrected.path("payload"), "2026-10-26");

    assertThat(ingestion.apply(body(corrected))).isEqualTo(IngestionOutcome.NEW_VERSION);
    assertThat(statusOf(id)).isEqualTo("CLOSED");
    assertThat(events.stream(NoticeClosed.class))
        .containsExactly(new NoticeClosed(id, LocalDate.of(2026, 10, 26)));
  }

  @Test
  void 한_공고의_일정을_읽지_못해도_다른_공고의_상태_재계산은_계속한다() {
    ingestion.apply(body(fixture()));
    ingestion.apply(body(otherPost(fixture())));
    UUID broken =
        jdbc.sql("select id from notice.notices order by created_at, id limit 1")
            .query(UUID.class)
            .single();
    jdbc.sql(
            "update notice.notice_versions set payload = jsonb_set(payload, '{notice,schedules}',"
                + " (select jsonb_agg(jsonb_set(jsonb_set(s, '{startsAt}',"
                + " '{\"precision\": \"DAY\", \"value\": \"broken\"}'), '{endsAt}',"
                + " '{\"precision\": \"DAY\", \"value\": \"broken\"}'))"
                + " from jsonb_array_elements(payload->'notice'->'schedules') s))"
                + " where notice_id = :id")
        .param("id", broken)
        .update();

    clock.set("2026-10-31T00:05");
    assertThat(status.refreshDue(10)).isEqualTo(2);
    assertThat(
            jdbc.sql("select count(*) from notice.notices where status = 'CLOSED'")
                .query(Long.class)
                .single())
        .isEqualTo(1); // 읽을 수 있는 공고는 마감 처리
    assertThat(
            jdbc.sql("select next_status_check_at > now() from notice.notices where id = :id")
                .param("id", broken)
                .query(Boolean.class)
                .single())
        .isTrue(); // 읽지 못한 공고는 하루 뒤로 미룸
  }

  @Test
  void 날짜가_없으면_상시_모집이_아니라_확인_필요다() {
    assertThat(statusWithRollingSchedule(false, false)).isEqualTo("ALWAYS");
    clean();
    assertThat(statusWithRollingSchedule(false, true)).isEqualTo("UNKNOWN");
  }

  @Test
  void 보류한_일정의_상시_모집은_접수_상태에_쓰지_않는다() {
    assertThat(statusWithRollingSchedule(true, false)).isEqualTo("UNKNOWN");
  }

  @Test
  void 하위_필드가_보류된_일정은_주기_재계산에서도_상시_모집이_아니다() {
    statusWithRollingSchedule(true, false);
    UUID id = onlyNotice();
    jdbc.sql(
            "update notice.notices set next_status_check_at = '2026-10-18T00:00:00Z' where id = :id")
        .param("id", id)
        .update();
    assertThat(status.refreshDue(200)).isEqualTo(1);
    assertThat(statusOf(id)).isEqualTo("UNKNOWN");
  }

  /**
   * 접수 기간 없이 상시 모집 일정만 있는 공고를 반영하고 접수 상태를 돌려줘요. held면 상시 모집 칸을 보류하고, notRolling이면 상시 모집이라고 적히지 않은
   * 날짜 없는 일정이에요.
   */
  private String statusWithRollingSchedule(boolean held, boolean notRolling) {
    ObjectNode event = fixture();
    ObjectNode payload = (ObjectNode) event.path("payload");
    payload.remove("schedule");
    payload.remove("applicationWindows");
    JsonNode schedules = payload.path("notice").path("schedules");
    for (int i = 0; i < schedules.size(); i++) {
      ObjectNode schedule = (ObjectNode) schedules.get(i);
      if (schedule.path("kinds").toString().contains("APPLICATION")) {
        schedule.put("applicationMode", notRolling ? "UNKNOWN" : "ROLLING");
        schedule.putNull("startsAt").putNull("endsAt");
        schedule.put("endStatus", "CONDITIONAL").put("endCondition", "소진 시까지");
        if (held) {
          ObjectNode item =
              json.createObjectNode()
                  .put("target", "/schedules/" + i + "/applicationMode")
                  .put("extent", "ITEM")
                  .put("description", "원문 확인이 필요합니다.");
          item.putArray("codes").add("CHECK");
          ((ArrayNode) payload.path("heldItems")).add(item);
          ((ArrayNode) payload.path("notice").path("quality").path("heldItems"))
              .add(item.deepCopy());
        }
      }
    }
    ingestion.apply(body(event));
    return statusOf(onlyNotice());
  }

  // ---------- 보류 항목 ----------

  @Test
  void 보류한_항목은_상세에서_빼고_확인_필요로_알린다() {
    ingestion.apply(body(fixture())); // 서리풀 공고는 자격 조건 하나를 보류했어요
    UUID id = onlyNotice();

    NoticeDetailResponse detail = query.detail(id);
    assertThat(detail.header().needsCheck()).isTrue();
    assertThat(detail.heldItems())
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.description()).isNotBlank());
    String heldTarget = detail.heldItems().getFirst().target();
    assertThat(heldTarget).startsWith("/eligibility/");
    int index = Integer.parseInt(heldTarget.split("/")[2]);
    assertThat(detail.conditions().get(index).inReview()).isTrue();

    ingestion.apply(body(otherPost(withStatus(fixture(), "APPROVED"))));
    assertThat(
            jdbc.sql("select needs_check from notice.notices order by created_at, id")
                .query(Boolean.class)
                .list())
        .containsExactly(true, false); // 승인본은 보류 항목이 없어요
  }

  // ---------- 정정 공고 ----------

  @Test
  void 정정_공고는_원래_공고의_새_버전으로_반영한다() {
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();

    assertThat(ingestion.apply(body(correctionOf(fixture(), "2026-11-06"))))
        .isEqualTo(IngestionOutcome.NEW_VERSION);

    NoticeDetailResponse detail = query.detail(id);
    assertThat(detail.version()).isEqualTo(2);
    assertThat(detail.header().applyEnd()).isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(count("notice.notice_posts")).isEqualTo(2);
    assertThat(query.versions(id))
        .extracting(v -> v.kind())
        .containsExactly("NOTICE", "CORRECTION");
    assertThat(events.stream(NoticeUpdated.class))
        .singleElement()
        .satisfies(e -> assertThat(e.noticeId()).isEqualTo(id));
  }

  @Test
  void 원래_공고보다_먼저_온_정정은_기다렸다가_원래_공고가_공개될_때_반영한다() {
    ObjectNode correction = correctionOf(fixture(), "2026-11-06");
    assertThat(ingestion.apply(body(correction))).isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notices")).isZero();
    assertThat(outcomes()).containsExactly("HELD:PARENT_NOT_PUBLISHED");

    assertThat(ingestion.apply(body(fixture()))).isEqualTo(IngestionOutcome.PUBLISHED);

    NoticeDetailResponse detail = query.detail(onlyNotice());
    assertThat(detail.version()).isEqualTo(2);
    assertThat(detail.header().applyEnd()).isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(outcomes()).containsExactlyInAnyOrder("NEW_VERSION", "PUBLISHED");
    assertThat(events.stream(NoticePublished.class)).hasSize(1);
    assertThat(events.stream(NoticeUpdated.class)).hasSize(1);
  }

  @Test
  void 기다리던_정정의_저장본이_계약에_맞지_않아도_원래_공고는_공개한다() {
    ObjectNode correction = correctionOf(fixture(), "2026-11-06");
    assertThat(ingestion.apply(body(correction))).isEqualTo(IngestionOutcome.HELD);
    // 계약 검사가 강화된 뒤라고 가정: 저장해 둔 정정이 지금 검사에 걸림
    jdbc.sql(
            "update notice.extraction_results set payload = jsonb_set(payload, '{reviewStatus}',"
                + " '\"DONE\"') where event_id = :id")
        .param("id", UUID.fromString(correction.path("eventId").asString()))
        .update();

    assertThat(ingestion.apply(body(fixture()))).isEqualTo(IngestionOutcome.PUBLISHED);
    assertThat(count("notice.notices")).isEqualTo(1);
    assertThat(outcomes()).contains("HELD:STORED_PAYLOAD_INVALID");
  }

  @Test
  void 정정이_반영된_뒤_원래_공고가_바뀌어도_정정_내용을_덮어쓰지_않는다() {
    ingestion.apply(body(fixture()));
    ingestion.apply(body(correctionOf(fixture(), "2026-11-06")));
    ObjectNode originalEdited = withRawVersion(fixture(), 2);
    originalEdited.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) originalEdited.path("payload")).put("title", "원래 공고 수정");
    ((ObjectNode) originalEdited.path("payload").path("notice").path("notice"))
        .put("title", "원래 공고 수정");

    assertThat(ingestion.apply(body(originalEdited))).isEqualTo(IngestionOutcome.KEPT_CORRECTION);
    assertThat(count("notice.notice_versions")).isEqualTo(2);
    assertThat(query.detail(onlyNotice()).header().applyEnd()).isEqualTo(LocalDate.of(2026, 11, 6));
  }

  @Test
  void 늦게_도착한_더_이른_정정은_최신_정정을_덮어쓰지_않는다() {
    ingestion.apply(body(fixture()));
    ObjectNode later = postedOn(correctionOf(fixture(), "2026-11-10"), "2026-10-15");
    ObjectNode earlier = postedOn(correctionOf(fixture(), "2026-11-06"), "2026-10-10");

    assertThat(ingestion.apply(body(later))).isEqualTo(IngestionOutcome.NEW_VERSION);
    assertThat(ingestion.apply(body(earlier))).isEqualTo(IngestionOutcome.KEPT_CORRECTION);

    assertThat(count("notice.notice_versions")).isEqualTo(2);
    assertThat(query.detail(onlyNotice()).header().applyEnd())
        .isEqualTo(LocalDate.of(2026, 11, 10));
    assertThat(outcomes()).contains("KEPT_CORRECTION:LATER_CORRECTION_APPLIED");
  }

  @Test
  void 검수_대기인_정정은_기존_공개_버전을_유지한다() {
    ingestion.apply(body(fixture()));

    assertThat(
            ingestion.apply(body(withStatus(correctionOf(fixture(), "2026-11-06"), "IN_REVIEW"))))
        .isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notice_versions")).isEqualTo(1);
  }

  // ---------- 후속 안내 ----------

  @Test
  void 후속_안내는_원래_공고의_버전을_바꾸지_않고_상세에_붙인다() {
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();

    assertThat(ingestion.apply(body(followUpOf(fixture(), "서류 제출 안내"))))
        .isEqualTo(IngestionOutcome.FOLLOW_UP_ATTACHED);

    assertThat(count("notice.notices")).isEqualTo(1);
    NoticeDetailResponse detail = query.detail(id);
    assertThat(detail.version()).isEqualTo(1);
    assertThat(detail.followUps())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.title()).isEqualTo("서류 제출 안내");
              assertThat(f.detailUrl()).startsWith("https://");
              assertThat(f.schedules()).isNotEmpty();
            });
    assertThat(events.stream(NoticeUpdated.class))
        .singleElement()
        .satisfies(e -> assertThat(e.changedFields()).containsExactly("followUps"));
  }

  @Test
  void 원래_공고보다_먼저_온_후속_안내는_기다렸다가_붙인다() {
    assertThat(ingestion.apply(body(followUpOf(fixture(), "당첨자 발표"))))
        .isEqualTo(IngestionOutcome.HELD);
    assertThat(outcomes()).containsExactly("HELD:PARENT_NOT_PUBLISHED");

    ingestion.apply(body(fixture()));

    assertThat(query.detail(onlyNotice()).followUps()).hasSize(1);
    assertThat(outcomes()).containsExactlyInAnyOrder("PUBLISHED", "FOLLOW_UP_ATTACHED");
  }

  @Test
  void 같은_후속_안내의_새_버전은_내용을_바꾸고_같은_내용이면_그대로_둔다() {
    ingestion.apply(body(fixture()));
    ObjectNode first = followUpOf(fixture(), "서류 제출 안내");
    ingestion.apply(body(first));

    ObjectNode same = first.deepCopy();
    same.put("eventId", UUID.randomUUID().toString());
    assertThat(ingestion.apply(body(same))).isEqualTo(IngestionOutcome.NO_CHANGE);

    ObjectNode edited = withRawVersion(first.deepCopy(), 2);
    edited.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) edited.path("payload").path("notice").path("sourceReferences").get(0))
        .put("sourceTitle", "서류 제출 안내 (일정 변경)");
    assertThat(ingestion.apply(body(edited))).isEqualTo(IngestionOutcome.FOLLOW_UP_ATTACHED);

    assertThat(count("notice.notice_follow_ups")).isEqualTo(1);
    assertThat(query.detail(onlyNotice()).followUps())
        .singleElement()
        .satisfies(f -> assertThat(f.title()).isEqualTo("서류 제출 안내 (일정 변경)"));
  }

  @Test
  void 승인한_후속_안내는_같은_원본_버전의_자동_결과로_덮어쓰지_않는다() {
    ingestion.apply(body(fixture()));
    ObjectNode automatic = followUpOf(fixture(), "서류 제출 안내");
    ObjectNode approved = withStatus(automatic.deepCopy(), "APPROVED");
    assertThat(ingestion.apply(body(approved))).isEqualTo(IngestionOutcome.FOLLOW_UP_ATTACHED);

    automatic.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) automatic.path("payload").path("notice").path("sourceReferences").get(0))
        .put("sourceTitle", "자동 결과 제목");

    assertThat(ingestion.apply(body(automatic))).isEqualTo(IngestionOutcome.KEPT_APPROVED);
    assertThat(query.detail(onlyNotice()).followUps())
        .singleElement()
        .satisfies(f -> assertThat(f.title()).isEqualTo("서류 제출 안내"));
  }

  @Test
  void 검수_대기인_후속_안내는_붙이지_않는다() {
    ingestion.apply(body(fixture()));

    assertThat(ingestion.apply(body(withStatus(followUpOf(fixture(), "발표"), "IN_REVIEW"))))
        .isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notice_follow_ups")).isZero();
  }

  // ---------- 도우미 ----------

  @Test
  void 새_버전을_만들지_않은_정정도_공고에_이어_그_정정을_다시_정정한_글이_반영된다() {
    // 원공고 N → 보이는 변화 없는 정정 C1(NO_CHANGE) → C1을 정정하는 C2: C1이 공고에 이어져 있어야 C2가 원래 공고를 찾아요
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();
    ObjectNode first = unchangedCorrection();
    assertThat(ingestion.apply(body(first))).isEqualTo(IngestionOutcome.NO_CHANGE);
    assertThat(count("notice.notice_posts")).isEqualTo(2);

    ObjectNode second = correctionOf(first.deepCopy(), "2026-11-02");
    assertThat(ingestion.apply(body(second))).isEqualTo(IngestionOutcome.NEW_VERSION);
    assertThat(query.detail(id).header().applyEnd()).isEqualTo(LocalDate.of(2026, 11, 2));
  }

  @Test
  void 정정의_정정이_먼저_와_기다리면_새_버전_없이_이어진_정정에서_풀린다() {
    ingestion.apply(body(fixture()));
    UUID id = onlyNotice();
    ObjectNode first = unchangedCorrection();
    ObjectNode second = correctionOf(first.deepCopy(), "2026-11-02");
    assertThat(ingestion.apply(body(second))).isEqualTo(IngestionOutcome.HELD);

    assertThat(ingestion.apply(body(first))).isEqualTo(IngestionOutcome.NO_CHANGE);

    assertThat(query.detail(id).header().applyEnd()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(outcomes()).doesNotContain("HELD:PARENT_NOT_PUBLISHED").contains("NEW_VERSION");
  }

  @Test
  void 검수_대기인_정정은_공고에_잇지_않아_그_정정을_다시_정정한_글은_기다린다() {
    ingestion.apply(body(fixture()));
    ObjectNode first = withStatus(correctionOf(fixture(), "2026-11-06"), "IN_REVIEW");
    assertThat(ingestion.apply(body(first))).isEqualTo(IngestionOutcome.HELD);
    assertThat(count("notice.notice_posts")).isEqualTo(1);

    ObjectNode second =
        withStatus(correctionOf(first.deepCopy(), "2026-11-02"), "PARTIALLY_PASSED");
    assertThat(ingestion.apply(body(second))).isEqualTo(IngestionOutcome.HELD);
    assertThat(outcomes()).last().isEqualTo("HELD:PARENT_NOT_PUBLISHED");
  }

  private ObjectNode load(String name) {
    try (InputStream in = getClass().getResourceAsStream(name)) {
      return (ObjectNode) json.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private ObjectNode fixture() {
    return load("/notice/extraction-completed-seoripul.json");
  }

  private static ObjectNode withStatus(ObjectNode event, String status) {
    ((ObjectNode) event.path("payload")).put("reviewStatus", status);
    if (status.equals("APPROVED")) {
      ((ObjectNode) event.path("payload")).remove("heldItems");
      ((ObjectNode) event.path("payload").path("notice").path("quality")).remove("heldItems");
      for (JsonNode c : event.path("payload").path("conditions")) {
        ((ObjectNode) c).put("inReview", false).putArray("reviewReasons");
      }
    }
    return event;
  }

  private static ObjectNode withRawVersion(ObjectNode event, int rawVersion) {
    ((ObjectNode) event.path("payload")).put("rawVersion", rawVersion);
    ((ObjectNode) event.path("payload").path("notice").path("input")).put("rawVersion", rawVersion);
    ((ObjectNode) event.path("payload").path("notice").path("sourceReferences").get(0))
        .put("rawVersion", rawVersion);
    return event;
  }

  /** 이 공고를 정정하는 다른 원본 글. 접수 마감일을 바꿔요. */
  private static ObjectNode correctionOf(ObjectNode original, String newApplyEndOn) {
    String originalPostId = original.path("payload").path("rawPostId").asString();
    String correctionPostId = UUID.randomUUID().toString();
    original.put("eventId", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) original.path("payload");
    payload.put("rawPostId", correctionPostId);
    ((ObjectNode) payload.path("notice").path("input")).put("rawPostId", correctionPostId);
    setApplicationEnd(payload, newApplyEndOn);
    ObjectNode reference = (ObjectNode) payload.path("notice").path("sourceReferences").get(0);
    reference.put("rawPostId", correctionPostId);
    reference.put("kind", "CORRECTION");
    reference.put("supersedesPostId", originalPostId);
    return original;
  }

  /** 원래 공고와 보이는 내용이 같은 정정 공고 (일정을 원래 공고 그대로 둬요). */
  private ObjectNode unchangedCorrection() {
    ObjectNode correction = correctionOf(fixture(), "2026-10-30");
    ObjectNode original = (ObjectNode) fixture().path("payload");
    ObjectNode payload = (ObjectNode) correction.path("payload");
    payload.set("schedule", original.path("schedule"));
    payload.set("applicationWindows", original.path("applicationWindows"));
    ((ObjectNode) payload.path("notice"))
        .set("schedules", original.path("notice").path("schedules"));
    return correction;
  }

  /** 접수 마감일을 바꿔요 (원문 일정과 목록용 칸 모두). */
  private static void setApplicationEnd(ObjectNode payload, String end) {
    ((ObjectNode) payload.path("schedule")).put("applyEndOn", end);
    ((ObjectNode) payload.path("applicationWindows").get(0)).put("applyEndOn", end);
    for (JsonNode schedule : payload.path("notice").path("schedules")) {
      if (schedule.path("kinds").toString().contains("APPLICATION")) {
        ((ObjectNode) schedule).putObject("endsAt").put("precision", "DAY").put("value", end);
      }
    }
  }

  private static int round = 1;

  /** 다른 공고: 다른 원본 글에 모집 회차(「N차」)가 다른 공고. */
  private static ObjectNode otherPost(ObjectNode event) {
    String title = event.path("payload").path("title").asString() + " " + (++round) + "차";
    ((ObjectNode) event.path("payload")).put("title", title);
    ((ObjectNode) event.path("payload").path("notice").path("notice")).put("title", title);
    event.put("eventId", UUID.randomUUID().toString());
    String id = UUID.randomUUID().toString();
    ((ObjectNode) event.path("payload")).put("rawPostId", id);
    ((ObjectNode) event.path("payload").path("notice").path("input")).put("rawPostId", id);
    ((ObjectNode) event.path("payload").path("notice").path("sourceReferences").get(0))
        .put("rawPostId", id);
    return event;
  }

  private String statusOf(UUID id) {
    return query.detail(id).header().status();
  }

  /** 원본 글 게시일을 바꿔요. */
  private static ObjectNode postedOn(ObjectNode event, String day) {
    ((ObjectNode) event.path("payload").path("notice").path("sourceReferences").get(0))
        .put("postedOn", day);
    return event;
  }

  /** 이 공고의 후속 안내 글 (원문 제목을 바꿔요). */
  private static ObjectNode followUpOf(ObjectNode original, String title) {
    ObjectNode followUp = correctionOf(original, "2026-10-30");
    ObjectNode reference =
        (ObjectNode) followUp.path("payload").path("notice").path("sourceReferences").get(0);
    reference.put("kind", "FOLLOW_UP");
    reference.put("sourceTitle", title);
    return followUp;
  }

  private String body(ObjectNode event) {
    return json.writeValueAsString(event);
  }

  private UUID onlyNotice() {
    List<UUID> ids = jdbc.sql("select id from notice.notices").query(UUID.class).list();
    assertThat(ids).hasSize(1);
    return ids.getFirst();
  }

  private long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  private List<String> outcomes() {
    return jdbc.sql(
            "select outcome || coalesce(':' || reason, '') from notice.extraction_results"
                + " order by received_at")
        .query(String.class)
        .list();
  }
}
