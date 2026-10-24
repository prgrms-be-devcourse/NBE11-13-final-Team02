package kr.mujuthaek.api.notice.service.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.event.NoticeReportAnswered;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.common.id.UuidV7;
import kr.mujuthaek.api.notice.dto.request.AdminReportResolveRequest;
import kr.mujuthaek.api.notice.dto.request.NoticeReportRequest;
import kr.mujuthaek.api.notice.dto.response.AdminReportDetailResponse;
import kr.mujuthaek.api.notice.dto.response.AdminReportListResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeReportCreatedResponse;
import kr.mujuthaek.api.notice.repository.NoticeReportRepository;
import kr.mujuthaek.api.notice.repository.NoticeReportRepository.Report;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 제보: 회원이 공고 값이 다르다고 알리고, 관리자가 처리해 답을 남겨요. 답장 발송은 {@link NoticeReportAnswered} 이벤트로 알림 쪽에 넘겨요.
 *
 * <p>제보 내용(칸 이름·값·설명)은 로그에 남기지 않아요.
 */
@Service
public class NoticeReportService {

  /** 회원 한 명이 하루(한국 시간)에 낼 수 있는 제보 수. */
  static final int DAILY_LIMIT = 20;

  static final List<String> STATUSES = List.of("NEW", "DOING", "DONE");
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final int LIST_LIMIT = 100;

  private final NoticeReportRepository reports;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public NoticeReportService(
      NoticeReportRepository reports, ApplicationEventPublisher events, Clock clock) {
    this.reports = reports;
    this.events = events;
    this.clock = clock;
  }

  /** 회원 제보 받기. 로그인한 회원만, 공개된 공고에만. */
  @Transactional
  public NoticeReportCreatedResponse report(
      UUID noticeId, NoticeReportRequest request, String reporter) {
    UUID reporterId = reporterId(reporter);
    if (blank(request.reportedValue()) && blank(request.note())) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "맞는 값이나 설명 중 하나는 적어 주세요");
    }
    if (reports.publishedTitle(noticeId).isEmpty()) {
      throw new CustomException(ErrorCode.NOTICE_NOT_FOUND);
    }
    Instant now = clock.instant();
    Instant todayStart = LocalDate.ofInstant(now, SEOUL).atStartOfDay(SEOUL).toInstant();
    if (reports.countSince(reporterId, todayStart) >= DAILY_LIMIT) {
      throw new CustomException(ErrorCode.RATE_LIMITED, "제보는 하루에 " + DAILY_LIMIT + "건까지 보낼 수 있어요");
    }
    UUID id = UuidV7.next();
    boolean added =
        reports.insert(
            new NoticeReportRepository.Row(
                id,
                noticeId,
                reporterId,
                request.field().trim(),
                trim(request.currentValue()),
                trim(request.reportedValue()),
                trim(request.note()),
                trim(request.screen()),
                now));
    if (!added) {
      throw new CustomException(ErrorCode.CONFLICT, "같은 칸에 보낸 제보를 아직 확인하고 있어요");
    }
    return new NoticeReportCreatedResponse(id, "NEW");
  }

  /** 관리자 목록 (최근 것부터 최대 100건). status가 없으면 모두. */
  @Transactional(readOnly = true)
  public AdminReportListResponse list(String status) {
    if (status != null && !STATUSES.contains(status)) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "status는 NEW·DOING·DONE 중 하나예요");
    }
    List<AdminReportListResponse.Item> items =
        reports.list(status, LIST_LIMIT).stream().map(NoticeReportService::item).toList();
    return new AdminReportListResponse(counts(), items);
  }

  @Transactional(readOnly = true)
  public AdminReportListResponse.Counts counts() {
    int[] c = reports.counts();
    return new AdminReportListResponse.Counts(c[0], c[1], c[2]);
  }

  @Transactional(readOnly = true)
  public AdminReportDetailResponse detail(UUID id) {
    Report report = find(id);
    List<AdminReportDetailResponse.Note> notes = new ArrayList<>();
    for (Report other : reports.sameGroup(report.noticeId(), report.field(), report.id())) {
      if (!blank(other.note()) || !blank(other.reportedValue())) {
        notes.add(
            new AdminReportDetailResponse.Note(
                other.reportedValue(), other.note(), other.status(), other.createdAt()));
      }
    }
    return new AdminReportDetailResponse(
        item(report),
        report.note(),
        notes,
        report.result(),
        report.reply(),
        report.handledBy(),
        report.startedAt(),
        report.handledAt());
  }

  /** 처리 시작 (새 제보 → 처리 중). 이미 처리 중·끝이면 그대로 둬요. */
  @Transactional
  public AdminReportDetailResponse start(UUID id, String admin) {
    find(id);
    reports.start(id, admin, clock.instant());
    return detail(id);
  }

  /**
   * 처리 끝. group이면 같은 공고·칸의 처리 전 제보도 같은 답으로 끝내요. 답이 있으면 제보한 회원마다 {@link NoticeReportAnswered}를 보내요.
   */
  @Transactional
  public AdminReportDetailResponse resolve(
      UUID id, AdminReportResolveRequest request, String admin) {
    Report report = find(id);
    if ("DONE".equals(report.status())) {
      throw new CustomException(ErrorCode.CONFLICT, "이미 처리한 제보예요");
    }
    List<UUID> ids = new ArrayList<>(List.of(id));
    if (request.group() == null || request.group()) {
      reports.sameGroup(report.noticeId(), report.field(), id).stream()
          .filter(r -> !"DONE".equals(r.status()))
          .forEach(r -> ids.add(r.id()));
    }
    String reply = trim(request.reply());
    List<Report> finished =
        reports.finish(ids, request.result().name(), reply, admin, clock.instant());
    if (reply != null) {
      for (Report done : finished) {
        events.publishEvent(
            new NoticeReportAnswered(
                done.id(), done.noticeId(), done.reporterId(), done.field(), done.result(), reply));
      }
    }
    return detail(id);
  }

  /** 탈퇴한 회원의 제보를 지워요 (요구사항 3-16). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int forget(UUID reporterId) {
    return reports.deleteByReporter(reporterId);
  }

  private Report find(UUID id) {
    return reports
        .find(id)
        .orElseThrow(() -> new CustomException(ErrorCode.NOTICE_REPORT_NOT_FOUND));
  }

  private static AdminReportListResponse.Item item(Report r) {
    return new AdminReportListResponse.Item(
        r.id(),
        r.status(),
        r.screen(),
        r.noticeId(),
        r.noticeTitle(),
        r.field(),
        r.currentValue(),
        r.reportedValue(),
        summary(r.note()),
        r.createdAt(),
        Math.max(r.openInGroup(), "DONE".equals(r.status()) ? 0 : 1),
        r.result());
  }

  private static UUID reporterId(String reporter) {
    if (reporter == null) {
      throw new CustomException(ErrorCode.UNAUTHENTICATED);
    }
    try {
      return UUID.fromString(reporter);
    } catch (IllegalArgumentException e) {
      throw new CustomException(ErrorCode.UNAUTHENTICATED);
    }
  }

  private static String summary(String note) {
    if (note == null) {
      return null;
    }
    String one = note.replaceAll("\\s+", " ").trim();
    return one.length() <= 40 ? one : one.substring(0, 40) + "…";
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return blank(value) ? null : value.trim();
  }
}
