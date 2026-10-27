package kr.mujuthaek.api.area.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.area.dto.request.AreaReportRequest;
import kr.mujuthaek.api.area.dto.request.AreaReportResolveRequest;
import kr.mujuthaek.api.area.dto.response.AreaReportResponse;
import kr.mujuthaek.api.area.repository.AreaReportRepository;
import kr.mujuthaek.api.area.repository.AreaReportRepository.Report;
import kr.mujuthaek.api.common.event.AreaReportAnswered;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.common.id.UuidV7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생활권 제보: 회원이 생활권 값이 다르다고 알리고, 관리자가 처리해 답을 남겨요(2026-10-25, 공고 제보와 같은 흐름). 답장 발송은 {@link
 * AreaReportAnswered} 이벤트로 넘겨요. 값을 다시 계산하는 연결은 아직 없어요(처리 기록만).
 *
 * <p>제보 내용은 로그에 남기지 않아요.
 */
@Service
public class AreaReportService {

  static final int DAILY_LIMIT = 20;
  static final List<String> STATUSES = List.of("NEW", "DOING", "DONE");
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final AreaReportRepository reports;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public AreaReportService(
      AreaReportRepository reports, ApplicationEventPublisher events, Clock clock) {
    this.reports = reports;
    this.events = events;
    this.clock = clock;
  }

  /** 회원 제보 받기. 로그인한 회원만, 생활권 위치를 계산한 공고에만. */
  @Transactional
  public AreaReportResponse.Created report(
      UUID noticeId, AreaReportRequest request, String reporter) {
    UUID reporterId = reporterId(reporter);
    if (blank(request.reportedValue()) && blank(request.note())) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "맞는 값이나 설명 중 하나는 적어 주세요");
    }
    if (!reports.hasLocations(noticeId)) {
      throw new CustomException(ErrorCode.NOT_FOUND, "생활권 정보가 있는 공고가 아니에요");
    }
    Instant now = clock.instant();
    Instant todayStart = LocalDate.ofInstant(now, SEOUL).atStartOfDay(SEOUL).toInstant();
    if (reports.countSince(reporterId, todayStart) >= DAILY_LIMIT) {
      throw new CustomException(ErrorCode.RATE_LIMITED, "제보는 하루에 " + DAILY_LIMIT + "건까지 보낼 수 있어요");
    }
    UUID id = UuidV7.next();
    boolean added =
        reports.insert(
            new Report(
                id,
                noticeId,
                trim(request.noticeLocationId()),
                reporterId,
                request.field().trim(),
                trim(request.currentValue()),
                trim(request.reportedValue()),
                trim(request.note()),
                trim(request.screen()),
                "NEW",
                null,
                null,
                null,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                null,
                null,
                0));
    if (!added) {
      throw new CustomException(ErrorCode.CONFLICT, "같은 항목에 보낸 제보를 아직 확인하고 있어요");
    }
    return new AreaReportResponse.Created(id, "NEW");
  }

  @Transactional(readOnly = true)
  public AreaReportResponse.ListView list(String status) {
    if (status != null && !STATUSES.contains(status)) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "status는 NEW·DOING·DONE 중 하나예요");
    }
    return new AreaReportResponse.ListView(
        counts(), reports.list(status, 100).stream().map(AreaReportService::item).toList());
  }

  @Transactional(readOnly = true)
  public AreaReportResponse.Counts counts() {
    int[] c = reports.counts();
    return new AreaReportResponse.Counts(c[0], c[1], c[2]);
  }

  @Transactional(readOnly = true)
  public AreaReportResponse.Detail detail(UUID id) {
    Report report = find(id);
    List<AreaReportResponse.Note> notes = new ArrayList<>();
    for (Report other : reports.sameGroup(report.noticeId(), report.field(), id)) {
      if (!blank(other.note()) || !blank(other.reportedValue())) {
        notes.add(
            new AreaReportResponse.Note(
                other.reportedValue(), other.note(), other.status(), other.createdAt()));
      }
    }
    return new AreaReportResponse.Detail(
        item(report),
        report.note(),
        notes,
        report.result(),
        report.reply(),
        report.handledBy(),
        report.startedAt(),
        report.handledAt());
  }

  @Transactional
  public AreaReportResponse.Detail start(UUID id, String admin) {
    find(id);
    reports.start(id, admin, clock.instant());
    return detail(id);
  }

  /** 처리 끝. group이면 같은 공고·항목의 처리 전 제보도 같이 끝내고, 답이 있으면 회원마다 이벤트를 보내요. */
  @Transactional
  public AreaReportResponse.Detail resolve(
      UUID id, AreaReportResolveRequest request, String admin) {
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
            new AreaReportAnswered(
                done.id(),
                done.noticeId(),
                done.reporterId(),
                done.field(),
                done.result(),
                reply,
                null)); // area는 공고 제목을 몰라요 (받는 쪽이 noticeId로 찾음)
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
        .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "제보를 찾을 수 없어요"));
  }

  private static AreaReportResponse.Item item(Report r) {
    String summary = r.note() == null ? null : r.note().replaceAll("\\s+", " ").trim();
    if (summary != null && summary.length() > 40) {
      summary = summary.substring(0, 40) + "…";
    }
    return new AreaReportResponse.Item(
        r.id(),
        r.status(),
        r.screen(),
        r.noticeId(),
        r.noticeLocationId(),
        r.field(),
        r.currentValue(),
        r.reportedValue(),
        summary,
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

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return blank(value) ? null : value.trim();
  }
}
