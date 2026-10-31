package kr.mujuthaek.api.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.notice.service.internal.NoticeIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 동시성 시나리오 4-1: 같은 추출 완료 이벤트(eventId 같음)를 100번 동시에 받아도 공고·버전·처리 기록·보낼 이벤트가 한 번 받은 것과 같은지. 10회 반복.
 * Testcontainers DB라 운영·dev·로컬 api_fe 데이터는 건드리지 않아요. perf/concurrency/run.sh가 CONCURRENCY=1로 돌려요.
 */
@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "notice.status-refresh.enabled=false"
    })
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "CONCURRENCY", matches = "1")
class IngestionConcurrencyTest {

  @Autowired NoticeIngestionService ingestion;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  @Test
  void sameEventReceivedAtOnceIsAppliedOnce() throws Exception {
    // 기준: 한 번만 받았을 때의 줄 수
    clean();
    ingestion.apply(body(event()));
    Map<String, Long> once = counts();

    List<Map<String, Object>> rounds = new ArrayList<>();
    boolean allPass = true;
    for (int round = 1; round <= ConcurrencyRunner.ROUNDS; round++) {
      clean();
      String body = body(event());
      Map<String, Integer> outcomes = ConcurrencyRunner.runAtOnce(i -> ingestion.apply(body));
      Map<String, Long> after = counts();
      boolean errors = outcomes.keySet().stream().anyMatch(k -> k.startsWith("예외:"));
      boolean pass = after.equals(once) && !errors;
      allPass &= pass;
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("round", round);
      r.put("pass", pass);
      r.put("outcomes", outcomes);
      r.put("db", after);
      rounds.add(r);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scenario", "4-1 공고 저장 리스너: 같은 eventId 동시 수신");
    result.put("criteria", "공고·버전·처리 기록·outbox 줄 수가 한 번 받은 것과 같고, 예외(→ 재시도·실패 큐) 없음");
    result.put("expectedDb", once);
    result.put("pass", allPass);
    result.put("rounds", rounds);
    ConcurrencyRunner.write("4-1_ingestion", result);
    assertThat(allPass).as("결과: %s", rounds).isTrue();
  }

  private void clean() {
    jdbc.sql(
            "truncate notice.notice_reports, notice.notice_versions, notice.notice_posts,"
                + " notice.notice_follow_ups, notice.notice_regions, notice.notice_source_heads,"
                + " notice.notice_duplicate_candidates, notice.notice_area_scores, notice.notices,"
                + " notice.extraction_results, notice.policy_duplicate_candidates,"
                + " notice.policy_judgments, common.processed_events, common.outbox")
        .update();
  }

  private Map<String, Long> counts() {
    Map<String, Long> out = new LinkedHashMap<>();
    for (String table :
        List.of(
            "notice.notices",
            "notice.notice_versions",
            "notice.notice_posts",
            "notice.extraction_results",
            "common.outbox")) {
      out.put(table, jdbc.sql("select count(*) from " + table).query(Long.class).single());
    }
    return out;
  }

  /** 실제 추출 결과(서리풀 공고)에 새 eventId */
  private ObjectNode event() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/notice/extraction-completed-seoripul.json")) {
      ObjectNode e = (ObjectNode) json.readTree(in);
      e.put("eventId", UUID.randomUUID().toString());
      return e;
    }
  }

  private String body(ObjectNode event) {
    return json.writeValueAsString(event);
  }
}
