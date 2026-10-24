package kr.mujuthaek.api.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.event.NoticeReportAnswered;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notice.NoticeApiFixture;
import kr.mujuthaek.api.notice.dto.request.AdminReportResolveRequest;
import kr.mujuthaek.api.notice.dto.request.AdminReportResolveRequest.Result;
import kr.mujuthaek.api.notice.dto.request.NoticeReportRequest;
import kr.mujuthaek.api.notice.repository.NoticeReportRepository;
import kr.mujuthaek.api.notice.service.internal.NoticeReportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 공고 제보: 회원이 보내고 관리자가 처리해 답을 남기는 흐름 (실제 DB). */
class NoticeReportServiceTest {

  private final NoticeApiFixture fixture = new NoticeApiFixture();
  private final List<Object> events = new ArrayList<>();
  private final NoticeReportService service =
      new NoticeReportService(
          new NoticeReportRepository(JdbcClient.create(fixture.jdbc)),
          events::add,
          Clock.fixed(Instant.parse("2026-10-25T03:00:00Z"), ZoneId.of("Asia/Seoul")));

  private static NoticeReportRequest deposit(String note) {
    return new NoticeReportRequest("보증금", "2,400만 원", "2,600만 원", note, "공고 상세");
  }

  private UUID notice() {
    return fixture.notice(p -> {});
  }

  @Test
  void reportIsListedAsNewWithGroupCount() {
    UUID notice = notice();
    String a = UUID.randomUUID().toString();
    String b = UUID.randomUUID().toString();
    UUID first = service.report(notice, deposit("공고문 4쪽"), a).id();
    service.report(notice, deposit("정정 공고에 2,600만 원"), b);

    var detail = service.detail(first);
    assertThat(detail.report().status()).isEqualTo("NEW");
    assertThat(detail.report().groupCount()).isEqualTo(2);
    assertThat(detail.report().noticeId()).isEqualTo(notice);
    assertThat(detail.notes()).extracting(n -> n.text()).containsExactly("정정 공고에 2,600만 원");
    assertThat(service.list("NEW").items()).extracting(i -> i.id()).contains(first);
  }

  @Test
  void sameMemberCannotReportSameFieldTwiceWhileOpen() {
    UUID notice = notice();
    String a = UUID.randomUUID().toString();
    service.report(notice, deposit("첫 제보"), a);
    assertThatThrownBy(() -> service.report(notice, deposit("또"), a))
        .isInstanceOfSatisfying(
            CustomException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
  }

  @Test
  void anonymousUnknownNoticeAndEmptyReportAreRefused() {
    UUID notice = notice();
    assertThatThrownBy(() -> service.report(notice, deposit("x"), null))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThatThrownBy(
            () -> service.report(UUID.randomUUID(), deposit("x"), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOTICE_NOT_FOUND));
    var empty = new NoticeReportRequest("보증금", "2,400만 원", " ", null, null);
    assertThatThrownBy(() -> service.report(notice, empty, UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void resolvingTheGroupAnswersEveryReporter() {
    UUID notice = notice();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID first = service.report(notice, deposit("4쪽"), a.toString()).id();
    service.report(notice, deposit("정정"), b.toString());

    service.start(first, "admin-1");
    assertThat(service.detail(first).report().status()).isEqualTo("DOING");

    var done =
        service.resolve(
            first, new AdminReportResolveRequest(Result.FIXED, "보증금을 고쳤어요", null), "admin-1");
    assertThat(done.report().status()).isEqualTo("DONE");
    assertThat(done.result()).isEqualTo("FIXED");
    assertThat(done.handledBy()).isEqualTo("admin-1");
    assertThat(done.notes()).allMatch(n -> n.status().equals("DONE"));
    assertThat(events)
        .extracting(e -> ((NoticeReportAnswered) e).reporterId())
        .containsExactlyInAnyOrder(a, b);
    assertThatThrownBy(
            () ->
                service.resolve(
                    first, new AdminReportResolveRequest(Result.KEPT, null, false), "admin-1"))
        .isInstanceOfSatisfying(
            CustomException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
  }

  @Test
  void resolvingWithoutReplySendsNothingAndCanSkipGroup() {
    UUID notice = notice();
    UUID first = service.report(notice, deposit("4쪽"), UUID.randomUUID().toString()).id();
    UUID second = service.report(notice, deposit("정정"), UUID.randomUUID().toString()).id();
    service.resolve(first, new AdminReportResolveRequest(Result.KEPT, " ", false), "admin-1");
    assertThat(events).isEmpty();
    assertThat(service.detail(second).report().status()).isEqualTo("NEW");
  }

  @Test
  void withdrawnMembersReportsAreDeleted() {
    UUID notice = notice();
    UUID member = UUID.randomUUID();
    UUID id = service.report(notice, deposit("4쪽"), member.toString()).id();
    assertThat(service.forget(member)).isEqualTo(1);
    assertThatThrownBy(() -> service.detail(id))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOTICE_REPORT_NOT_FOUND));
  }
}
