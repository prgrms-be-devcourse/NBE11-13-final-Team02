package kr.mujuthaek.api.eligibility.service;

import static kr.mujuthaek.api.notice.NoticeApiFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.CountingDataSource;
import kr.mujuthaek.api.TestDatabase;
import kr.mujuthaek.api.area.repository.PostgresLocationScores;
import kr.mujuthaek.api.area.repository.PostgresNoticeLocations;
import kr.mujuthaek.api.area.repository.PostgresSiteDetails;
import kr.mujuthaek.api.area.service.NoticeAreaQueryService;
import kr.mujuthaek.api.common.region.RegionQueryService;
import kr.mujuthaek.api.eligibility.dto.request.NoticeSearchRequest;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore;
import kr.mujuthaek.api.eligibility.repository.LoanProductStore;
import kr.mujuthaek.api.eligibility.rule.Overall;
import kr.mujuthaek.api.notice.NoticeApiFixture;
import kr.mujuthaek.api.notice.NoticeFixtures;
import kr.mujuthaek.api.notice.repository.AreaScoreRepository;
import kr.mujuthaek.api.notice.repository.FollowUpRepository;
import kr.mujuthaek.api.notice.repository.NoticeQueries;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import kr.mujuthaek.api.notice.service.NoticeQueryService.JudgingSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 맞춤 목록이 공고 수만큼 쿼리를 늘리지 않는지, 추출이 덜 된 공고를 확인 필요로 두는지. */
class NoticeSearchLoadTest {

  private final NoticeApiFixture f = new NoticeApiFixture();
  private final CountingDataSource counting = new CountingDataSource(TestDatabase.dataSource());
  private final NoticeQueryService queries = queries(counting);
  private final JdbcTemplate countingJdbc = new JdbcTemplate(counting.dataSource());
  private final CriteriaService criteria =
      new CriteriaService(
          new CriteriaStore(countingJdbc),
          new LoanProductStore(countingJdbc),
          NoticeFixtures.JSON,
          Clock.systemUTC());
  private final NoticeEvaluationService evaluations =
      new NoticeEvaluationService(
          queries,
          criteria,
          new PrepareCostService(new LoanProductStore(countingJdbc), criteria, Clock.systemUTC()));
  private final NoticeSearchService search = new NoticeSearchService(queries, evaluations);

  @Test
  void queryCountDoesNotGrowWithTheNumberOfNotices() {
    seed(3);
    int fewFirst = statements();
    int fewAgain = statements();
    seed(12);
    int manyFirst = statements();
    int manyAgain = statements();

    assertThat(manyFirst).isEqualTo(fewFirst); // 처음 읽을 때도 공고 수와 상관없이 같아요
    assertThat(manyAgain).isEqualTo(fewAgain);
    assertThat(fewAgain).isLessThan(fewFirst); // 두 번째부터는 기억해 둔 판정 틀을 써요
  }

  @Test
  void partlyExtractedNoticesAreNeitherEligibleNorIneligible() {
    var evaluate =
        evaluations.evaluator(
            new kr.mujuthaek.api.eligibility.rule.ApplicantBuilder().birthMonth(1997, 3).build());
    var fits = ageFrame(19, 39);
    var tooOld = ageFrame(40, 50);

    assertThat(evaluate.apply(source(fits, true)).overall()).isEqualTo(Overall.ELIGIBLE);
    assertThat(evaluate.apply(source(tooOld, true)).overall()).isEqualTo(Overall.INELIGIBLE);

    var partlyFits = evaluate.apply(source(fits, false));
    assertThat(partlyFits.overall()).isEqualTo(Overall.NEEDS_INPUT);
    assertThat(partlyFits.reason()).contains("다 읽지 못했어요");
    assertThat(partlyFits.targets()).allMatch(t -> t.overall() == Overall.NEEDS_INPUT);
    // 조건별 결과는 그대로 보여 줘요 (상세의 체크)
    assertThat(partlyFits.targets().getFirst().summary().met()).isEqualTo(1);
    assertThat(evaluate.apply(source(tooOld, false)).overall()).isEqualTo(Overall.NEEDS_INPUT);
    assertThat(evaluate.apply(source(null, true)).overall()).isEqualTo(Overall.NEEDS_INPUT);
  }

  @Test
  void contractExamplePayloadCountsAsFullyExtracted() {
    UUID id = f.notice(p -> schedule(p, "2026-10-01", "2026-11-30"));
    assertThat(queries.judgingSources(List.of(id)).get(id).eligibilityComplete()).isTrue();
  }

  private int statements() {
    counting.reset();
    search.search(
        new NoticeSearchRequest(
            new NoticeSearchRequest.Filters(
                null, null, null, null, List.of(f.provider), null, null),
            null,
            null,
            true,
            null,
            20));
    return counting.statements();
  }

  private void seed(int total) {
    while (count() < total) {
      f.notice(
          p -> {
            schedule(p, "2026-10-01", "2026-11-30");
            NoticeFixtures.judgment(p, ageFrame(19, 39));
          });
    }
  }

  private int count() {
    return f.jdbc.queryForObject(
        "select count(*) from notice.notices where provider = ?", Integer.class, f.provider);
  }

  private static JudgingSource source(tools.jackson.databind.JsonNode frame, boolean complete) {
    return new JudgingSource(UUID.randomUUID(), "HOUSING", null, frame, List.of(), complete);
  }

  private static tools.jackson.databind.JsonNode ageFrame(int min, int max) {
    return NoticeFixtures.JSON.readTree(
        """
        {"schema":"housing-judgment.v1","rawPostId":"x","rawVersion":1,"referenceDate":"2026-10-01",
         "common":[],"groups":[{"id":"youth","name":"청년","criteria":[
           {"kind":"AGE","id":"age","quote":null,"page":null,"inReview":false,"reviewReason":null,
            "minAge":%d,"maxAge":%d}]}],"notes":[]}"""
            .formatted(min, max));
  }

  private static NoticeQueryService queries(CountingDataSource counting) {
    var jdbc = new JdbcTemplate(counting.dataSource());
    var client = JdbcClient.create(jdbc);
    return new NoticeQueryService(
        new NoticeQueries(jdbc),
        new FollowUpRepository(client),
        new AreaScoreRepository(client),
        new RegionQueryService(jdbc),
        new NoticeAreaQueryService(
            new PostgresNoticeLocations(counting.dataSource()),
            new PostgresSiteDetails(counting.dataSource()),
            new PostgresLocationScores(counting.dataSource())),
        Clock.fixed(
            NoticeApiFixture.TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul")),
        NoticeFixtures.JSON);
  }
}
