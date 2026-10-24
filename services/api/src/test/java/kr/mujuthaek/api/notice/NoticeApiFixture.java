package kr.mujuthaek.api.notice;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.common.exception.GlobalExceptionHandlerAccess;
import kr.mujuthaek.api.common.region.RegionQueryService;
import kr.mujuthaek.api.notice.repository.NoticeQueries;
import kr.mujuthaek.api.notice.repository.NoticeStore;
import kr.mujuthaek.api.notice.service.NoticeIngestService;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.ObjectNode;

/** 공고 API 테스트: 실제 DB에 공고를 저장하고, 오늘을 2026-10-22로 고정한 MockMvc로 불러요. */
public class NoticeApiFixture {

  public static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

  public final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.dataSource());
  public final NoticeIngestService ingest =
      new NoticeIngestService(new NoticeStore(jdbc, NoticeFixtures.JSON), event -> {});
  public final NoticeQueryService queries =
      new NoticeQueryService(
          new NoticeQueries(jdbc),
          new RegionQueryService(jdbc),
          Clock.fixed(
              TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
          NoticeFixtures.JSON);

  /** 테스트마다 다른 기관 이름이라, providers 필터로 이 테스트의 공고만 봐요. */
  public final String provider = "T" + UUID.randomUUID().toString().substring(0, 8);

  public MockMvc mvc(Object... controllers) {
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(GlobalExceptionHandlerAccess.handler())
        .build();
  }

  /** 공개된 공고 하나를 저장하고 공고 ID를 돌려줘요. edit로 payload를 바꿔요. */
  public UUID notice(java.util.function.Consumer<ObjectNode> edit) {
    UUID raw = UUID.randomUUID();
    ObjectNode p = NoticeFixtures.payload(raw, 1);
    p.put("provider", provider);
    edit.accept(p);
    ingest.accept(UUID.randomUUID(), p);
    return jdbc.queryForObject(
        "select id from notice.notices where raw_post_id = ?", UUID.class, raw);
  }

  public static void schedule(ObjectNode p, String start, String end) {
    ObjectNode s = (ObjectNode) p.get("schedule");
    s.put("applyStartOn", start);
    s.put("applyEndOn", end);
  }
}
