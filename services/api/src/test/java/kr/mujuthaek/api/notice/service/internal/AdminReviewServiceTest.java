package kr.mujuthaek.api.notice.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.OffsetDateTime;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.notice.client.ExtractionReviewClient;
import kr.mujuthaek.api.notice.client.ExtractionReviewProperties;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest;
import kr.mujuthaek.api.notice.dto.request.AdminReviewDecisionRequest.RejectReason;
import kr.mujuthaek.api.notice.dto.response.AdminReviewDetailResponse.Issue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 검수 API 응답을 관리자 화면 모양으로 옮기는 규칙. 가짜 검수 API를 써요. */
class AdminReviewServiceTest {

  private static final String BASE = "http://extraction.test";
  private static final String A = "11111111-1111-1111-1111-111111111111";
  private static final String B = "22222222-2222-2222-2222-222222222222";
  private static final String C = "33333333-3333-3333-3333-333333333333";
  private static final String LIST =
      """
      {"items":[
        {"jobId":"%s","jobStatus":"IN_REVIEW","reviewStatus":"PENDING","title":"가상 행복주택",
         "sourceTitle":"가상 글","sourceCode":"lh_myhome","rawVersion":2,
         "queuedAt":"2026-10-24 21:00:00.5+00:00","reasons":["VALUE_NOT_IN_QUOTE /eligibility/3/value","VALUE_NOT_IN_QUOTE /eligibility/3/value","EVIDENCE_SCATTERED /eligibility/4"]},
        {"jobId":"%s","jobStatus":"PARTIAL","reviewStatus":"PENDING","title":null,
         "sourceTitle":"가상 국민임대","sourceCode":"sh_board","rawVersion":1,
         "queuedAt":"2026-10-25T01:00:00+00:00","reasons":["SUPPLY_PRICE_MISSING"]},
        {"jobId":"%s","jobStatus":"INTAKE_REVIEW","reviewStatus":"PENDING","title":null,
         "sourceTitle":"가상 장기전세","sourceCode":"sh_board","rawVersion":1,
         "queuedAt":"2026-10-25T02:00:00+00:00","reasons":[]}
      ]}
      """
          .formatted(A, B, C);

  private MockRestServiceServer server;

  private AdminReviewService service() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    var client =
        new ExtractionReviewClient(
            new ExtractionReviewProperties(BASE, null, Duration.ofSeconds(1)), builder);
    return new AdminReviewService(client);
  }

  private void expectList(int times) {
    server
        .expect(ExpectedCount.times(times), requestTo(BASE + "/reviews?status=PENDING"))
        .andRespond(withSuccess(LIST, MediaType.APPLICATION_JSON));
  }

  @Test
  void pendingGroupsReasonsByJobStatusAndFallsBackToSourceTitle() {
    var service = service();
    expectList(1);
    var list = service.pending(null);
    assertThat(list.total()).isEqualTo(3);
    assertThat(list.items())
        .extracting(i -> i.reason() + " " + i.title() + " " + i.issueCount())
        .containsExactly("MUST 가상 행복주택 2", "CHECK 가상 국민임대 1", "FORMAT 가상 장기전세 0");
    assertThat(list.items().get(0).receivedAt())
        .isEqualTo(OffsetDateTime.parse("2026-10-24T21:00:00.5Z"));
  }

  @Test
  void pendingFiltersByReasonButKeepsTotal() {
    var service = service();
    expectList(1);
    var list = service.pending("FORMAT");
    assertThat(list.total()).isEqualTo(3);
    assertThat(list.items()).extracting(i -> i.id()).containsExactly(C);
  }

  @Test
  void unknownReasonIsRejectedWithoutCallingExtraction() {
    var service = service();
    assertThatThrownBy(() -> service.pending("LOW")).isInstanceOf(CustomException.class);
    server.verify();
  }

  @Test
  void countsGivesPendingAndOldest() {
    var service = service();
    expectList(1);
    var counts = service.counts();
    assertThat(counts.reviewPending()).isEqualTo(3);
    assertThat(counts.oldestReceivedAt()).isEqualTo(OffsetDateTime.parse("2026-10-24T21:00:00.5Z"));
  }

  @Test
  void detailUsesCandidateTitleAndRawEvent() {
    var service = service();
    server
        .expect(requestTo(BASE + "/reviews/" + A))
        .andRespond(
            withSuccess(
                """
                {"jobId":"%s","jobStatus":"IN_REVIEW","reviewStatus":"REJECTED","note":"[중복 공고]",
                 "reasons":["CRITERION_NOT_STRUCTURED /eligibility/10"],"decidedAt":"2026-10-25 03:00:00+00:00",
                 "rawPostId":"44444444-4444-4444-4444-444444444444",
                 "candidate":{"notice":{"title":"가상 행복주택 모집"}},
                 "rawEvent":{"occurredAt":"2026-10-24T20:00:00Z","payload":{"field":"HOUSING","provider":"LH","sourceCode":"lh_myhome","version":2,"title":"가상 글"}}}
                """
                    .formatted(A),
                MediaType.APPLICATION_JSON));
    var detail = service.detail(A);
    assertThat(detail.title()).isEqualTo("가상 행복주택 모집");
    assertThat(detail.reason()).isEqualTo("MUST");
    assertThat(detail.provider()).isEqualTo("LH");
    assertThat(detail.rawVersion()).isEqualTo(2);
    assertThat(detail.issues())
        .containsExactly(new Issue("CRITERION_NOT_STRUCTURED", "/eligibility/10"));
    assertThat(detail.log())
        .extracting(l -> l.who() + " " + l.text())
        .containsExactly("시스템 검수 대기열에 추가", "검수자 반려");
  }

  @Test
  void rejectPutsReasonLabelBeforeNoteAndReturnsRemaining() {
    var service = service();
    server
        .expect(requestTo(BASE + "/reviews/" + B + "/reject"))
        .andExpect(content().json("{\"note\":\"[원문과 다름 · 다시 추출] 표가 잘렸어요\"}"))
        .andRespond(withSuccess("{\"reviewStatus\":\"REJECTED\"}", MediaType.APPLICATION_JSON));
    expectList(1);
    var result =
        service.reject(
            B, new AdminReviewDecisionRequest(RejectReason.SOURCE_MISMATCH, " 표가 잘렸어요 "));
    assertThat(result.reviewStatus()).isEqualTo("REJECTED");
    assertThat(result.pendingCount()).isEqualTo(3);
  }

  @Test
  void issuesAcceptObjectsAndSkipBlanks() {
    var reasons =
        JsonMapper.builder()
            .build()
            .readTree("[{\"code\":\"A\",\"target\":\"/x\"},\"\",\"B\",\"B\"]");
    assertThat(AdminReviewService.issues(reasons))
        .containsExactly(new Issue("A", "/x"), new Issue("B", null));
  }

  @Test
  void rejectNoteWithoutReasonOrNote() {
    assertThat(AdminReviewService.rejectNote(null)).isNull();
    assertThat(AdminReviewService.rejectNote(new AdminReviewDecisionRequest(null, "  "))).isNull();
    assertThat(
            AdminReviewService.rejectNote(
                new AdminReviewDecisionRequest(RejectReason.CLOSED, null)))
        .isEqualTo("[마감된 공고]");
  }
}
