package kr.mujuthaek.api.notice.service.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujuthaek.api.common.event.NoticePublished;
import kr.mujuthaek.api.common.event.NoticeUpdated;
import kr.mujuthaek.api.common.id.UuidV7;
import kr.mujuthaek.api.notice.domain.CompletedNotice;
import kr.mujuthaek.api.notice.domain.IngestionOutcome;
import kr.mujuthaek.api.notice.domain.PublicationPolicy;
import kr.mujuthaek.api.notice.domain.PublicationPolicy.Decision;
import kr.mujuthaek.api.notice.mapper.ExtractionEvent;
import kr.mujuthaek.api.notice.mapper.NoticeContent;
import kr.mujuthaek.api.notice.mapper.NoticeContent.Source;
import kr.mujuthaek.api.notice.repository.ExtractionResultRepository;
import kr.mujuthaek.api.notice.repository.ExtractionResultRepository.WaitingResult;
import kr.mujuthaek.api.notice.repository.FollowUpRepository;
import kr.mujuthaek.api.notice.repository.FollowUpRepository.Attached;
import kr.mujuthaek.api.notice.repository.FollowUpRepository.FollowUpRow;
import kr.mujuthaek.api.notice.repository.NoticeRepository;
import kr.mujuthaek.api.notice.repository.NoticeRepository.CurrentVersion;
import kr.mujuthaek.api.notice.repository.NoticeRepository.SourceVersion;
import kr.mujuthaek.api.notice.repository.NoticeRepository.VersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * extraction.completed 한 건을 공고로 반영해요. 한 트랜잭션 안에서 수신 기록 → 공개 판단 → 버전 저장 → 이벤트 발행까지 하고, 어느 단계에서 실패해도
 * 모두 되돌려져 같은 메시지를 다시 받으면 처음부터 처리돼요.
 *
 * <ul>
 *   <li>검수 대기·상세 없음은 공개하지 않고 기록만 남겨요. 공개 중인 버전은 그대로예요.
 *   <li>공개 버전은 고치지 않아요. 내용이 바뀐 결과만 새 버전을 만들고, 같은 내용의 승인본은 검수 상태만 승인으로 올려요.
 *   <li>정정 공고: 수집기가 알려 준 원래 공고(supersedesPostId)의 새 버전으로 반영해요. 원래 공고가 아직 공개 전이면 기다렸다가
 *       (PARENT_NOT_PUBLISHED), 원래 공고가 공개될 때 같은 트랜잭션에서 이어서 반영해요. 정정이 반영된 뒤 원래 공고 글이 바뀌어도 정정 내용을
 *       덮어쓰지 않아요.
 *   <li>후속 안내: 원래 공고의 버전을 바꾸지 않고 따로 붙여요(attachFollowUp).
 * </ul>
 */
@Service
public class NoticeIngestionService {

  private static final Logger log = LoggerFactory.getLogger(NoticeIngestionService.class);
  private static final Set<String> NOTICE_FIELDS = Set.of("HOUSING", "BUSINESS_SPACE");

  /** 후속 안내가 붙거나 바뀌었을 때 NoticeUpdated.changedFields에 넣는 이름. */
  static final String FOLLOW_UPS = "followUps";

  /** 내용 해시는 다르지만 비교하는 섹션은 모두 같음 (근거 위치·검증 기록 등만 바뀜). */
  static final String NO_VISIBLE_CHANGE = "NO_VISIBLE_CHANGE";

  private final NoticeRepository notices;
  private final ExtractionResultRepository results;
  private final FollowUpRepository followUps;
  private final ApplicationEventPublisher events;
  private final JsonMapper json;
  private final Clock clock;

  public NoticeIngestionService(
      NoticeRepository notices,
      ExtractionResultRepository results,
      FollowUpRepository followUps,
      ApplicationEventPublisher events,
      JsonMapper json,
      Clock clock) {
    this.notices = notices;
    this.results = results;
    this.followUps = followUps;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  /** 받은 메시지 본문(봉투 JSON)을 반영하고 결과를 돌려줘요. 계약에 맞지 않으면 ExtractionEvent.InvalidEventException. */
  @Transactional
  public IngestionOutcome apply(String messageBody) {
    ExtractionEvent event = ExtractionEvent.from(parse(messageBody));
    if (results.exists(event.eventId())) {
      return IngestionOutcome.DUPLICATE;
    }
    Instant now = clock.instant();
    Source source = NoticeContent.source(event);
    if (!NOTICE_FIELDS.contains(event.field())) {
      Decision decision = new Decision(IngestionOutcome.UNSUPPORTED_FIELD, event.field());
      record(event, source, decision, null, now);
      return decision.outcome();
    }
    Applied applied = ingest(event, source, now);
    record(event, source, applied.decision(), applied.noticeId(), now);
    replayWaiting(event, applied, now);
    return applied.decision().outcome();
  }

  /**
   * 반영 결과. noticeId는 반영하거나 가리킨 공고 (없으면 null). linked는 이번에 이 글을 공고에 처음 이었는지(새 버전 없이 이은 정정 포함)로, 이 글을
   * 기다리던 정정·후속 안내를 이어서 반영할 때 봐요.
   */
  private record Applied(Decision decision, UUID noticeId, boolean linked) {

    Applied(Decision decision, UUID noticeId) {
      this(decision, noticeId, false);
    }
  }

  private Applied ingest(ExtractionEvent event, Source source, Instant now) {
    if (source.unlinked()) {
      // 연결 없는 정정을 일반 공고로 공개하면 같은 공고가 둘로 보여요. 받은 payload(원래 종류 포함)는 수신 기록에 남아요
      return logged(
          event,
          new Applied(
              new Decision(
                  IngestionOutcome.HELD, ExtractionResultRepository.UNLINKED_RELATED_NOTICE),
              null));
    }
    if (source.followUp()) {
      return logged(event, attachFollowUp(event, source, now));
    }
    if (source.correction()) {
      notices.lockRawPost(source.supersedesPostId()); // 원래 공고 먼저: 원래 공고 처리와 순서가 엇갈리지 않게
    }
    notices.lockRawPost(event.rawPostId());

    Optional<UUID> noticeId = notices.findNoticeIdByPost(event.rawPostId());
    boolean linkedBefore = noticeId.isPresent();
    if (noticeId.isEmpty() && source.correction()) {
      noticeId = notices.findNoticeIdByPost(source.supersedesPostId());
    }
    Optional<CurrentVersion> current = noticeId.flatMap(notices::lockCurrent);
    String contentHash = NoticeContent.hash(event);
    Decision decision =
        PublicationPolicy.decide(
            current.map(c -> published(c, event)).orElse(null),
            new PublicationPolicy.Incoming(
                event.rawVersion(),
                event.reviewStatus(),
                event.notice() != null,
                contentHash,
                source.correction(),
                source.postedOn()));

    if (decision.outcome() == IngestionOutcome.PUBLISHED && source.correction()) {
      // 원래 공고가 아직 공개되지 않음 (검수 대기거나 아직 안 옴): 원래 공고가 공개될 때 이어서 반영해요
      decision =
          new Decision(IngestionOutcome.HELD, ExtractionResultRepository.PARENT_NOT_PUBLISHED);
    } else if (decision.outcome() == IngestionOutcome.PUBLISHED) {
      noticeId = Optional.of(publishNew(event, source, contentHash, now));
    } else if (decision.outcome() == IngestionOutcome.NEW_VERSION) {
      List<String> changed = changedSections(current.orElseThrow(), event);
      if (changed.isEmpty()) {
        // 근거 인용 위치·검증 기록·문서 정보·수집 기록처럼 사용자에게 의미 없는 부분만 바뀜: 새 버전과 「바뀐 것 없는 정정」 알림을 만들지 않아요
        decision = new Decision(IngestionOutcome.NO_CHANGE, NO_VISIBLE_CHANGE);
      } else {
        publishVersion(current.orElseThrow(), event, source, contentHash, changed, now);
      }
    } else if (decision.outcome() == IngestionOutcome.APPROVAL_RECORDED) {
      // 내용은 그대로라 새 버전·알림 없이 검수 상태만 올려요. 그래야 같은 원본 버전의 자동 결과가 승인 내용을 덮어쓰지 않아요
      notices.markCurrentApproved(
          current.orElseThrow().noticeId(), event.rawPostId(), event.rawVersion(), now);
    }
    boolean linked = false;
    if (!linkedBefore
        && source.correction()
        && current.isPresent()
        && !decision.publishes() // 새 버전으로 반영했으면 publishVersion이 이미 이었어요
        && decision.outcome() != IngestionOutcome.HELD) {
      // 원래 공고에 속한 정정이면 새 버전을 만들지 않았어도(보이는 변화 없음·내용 같음·더 늦은 정정이 있음 등) 글을 공고에 이어요.
      // 잇지 않으면 이 정정을 다시 정정한 글이 원래 공고를 찾지 못해 PARENT_NOT_PUBLISHED로 계속 기다려요.
      // 검수 대기·상세 없음(HELD)은 공고 내용에 들어가지 않은 글이라 잇지 않아요
      notices.upsertPost(
          event.rawPostId(),
          current.get().noticeId(),
          source.kind(),
          source.supersedesPostId(),
          source.postedOn(),
          now);
      linked = true;
    }
    return logged(event, new Applied(decision, noticeId.orElse(null), linked));
  }

  /** 후속 안내를 원래 공고에 붙여요. 공고 버전은 그대로 두고, 원래 공고가 아직 공개 전이면 기다렸다가 공개될 때 붙여요. */
  private Applied attachFollowUp(ExtractionEvent event, Source source, Instant now) {
    notices.lockRawPost(source.supersedesPostId());
    notices.lockRawPost(event.rawPostId());
    Optional<Attached> attached = followUps.findByRawPost(event.rawPostId());
    Optional<UUID> noticeId =
        attached
            .map(Attached::noticeId)
            .or(() -> notices.findNoticeIdByPost(source.supersedesPostId()));
    String contentHash = NoticeContent.hash(event);
    Decision decision =
        PublicationPolicy.decideFollowUp(
            attached
                .map(
                    a ->
                        new PublicationPolicy.AttachedFollowUp(
                            a.rawVersion(), a.contentHash(), a.reviewStatus()))
                .orElse(null),
            new PublicationPolicy.Incoming(
                event.rawVersion(),
                event.reviewStatus(),
                event.notice() != null,
                contentHash,
                false),
            noticeId.isPresent());
    if (decision.outcome() == IngestionOutcome.FOLLOW_UP_ATTACHED) {
      followUps.upsert(
          new FollowUpRow(
              event.rawPostId(),
              noticeId.orElseThrow(),
              event.rawVersion(),
              event.eventId(),
              source.sourceTitle() != null ? source.sourceTitle() : event.title(),
              source.detailUrl(),
              source.postedOn(),
              json.writeValueAsString(NoticeContent.schedules(event)),
              contentHash,
              event.reviewStatus()),
          now);
      events.publishEvent(
          new NoticeUpdated(noticeId.orElseThrow(), List.of(FOLLOW_UPS), event.reprocess()));
    } else if (decision.outcome() == IngestionOutcome.APPROVAL_RECORDED) {
      followUps.markApproved(event.rawPostId(), now);
    }
    return new Applied(decision, noticeId.orElse(null));
  }

  private PublicationPolicy.Published published(CurrentVersion current, ExtractionEvent event) {
    Optional<SourceVersion> fromPost = notices.latestFromPost(event.rawPostId());
    return new PublicationPolicy.Published(
        fromPost.map(SourceVersion::rawVersion).orElse(null),
        fromPost.map(SourceVersion::reviewStatus).orElse(null),
        current.contentHash(),
        notices.hasCorrection(current.noticeId()),
        notices.latestOtherCorrectionPostedOn(current.noticeId(), event.rawPostId()).orElse(null));
  }

  /**
   * 방금 공개했거나 공고에 처음 이은 글을 기다리던 정정 공고를 이어서 반영해요. 반영한 정정을 기다리던 정정도 차례로 반영돼요. 새 버전 없이 이은 정정(보이는 변화 없음
   * 등)을 기다리던 정정도 여기서 풀려요.
   */
  private void replayWaiting(ExtractionEvent parent, Applied parentApplied, Instant now) {
    if (!parentApplied.decision().publishes() && !parentApplied.linked()) {
      return;
    }
    for (WaitingResult waiting : results.findWaitingFor(parent.rawPostId())) {
      ExtractionEvent correction;
      try {
        correction = ExtractionEvent.fromStored(waiting.eventId(), parse(waiting.payloadJson()));
      } catch (ExtractionEvent.InvalidEventException e) {
        // 저장해 둔 정정이 지금 계약(검사가 강화됨)에 맞지 않음: 이 정정만 보류로 남기고 원래 공고는 그대로 공개해요.
        // 예외를 그대로 두면 원래 공고 메시지까지 실패 큐로 가고, 다시 넣어도 같은 이유로 실패해 원래 공고가 공개되지 않아요
        log.warn(
            "기다리던 정정의 저장본이 계약에 맞지 않아 보류합니다 eventId={} reason={}",
            waiting.eventId(),
            e.getMessage());
        results.updateOutcome(
            waiting.eventId(),
            IngestionOutcome.HELD,
            ExtractionResultRepository.STORED_PAYLOAD_INVALID,
            null);
        continue;
      }
      Applied applied = ingest(correction, NoticeContent.source(correction), now);
      results.updateOutcome(
          waiting.eventId(),
          applied.decision().outcome(),
          applied.decision().reason(),
          applied.noticeId());
      replayWaiting(correction, applied, now);
    }
  }

  private UUID publishNew(ExtractionEvent event, Source source, String contentHash, Instant now) {
    UUID noticeId = UuidV7.next();
    CompletedNotice listValues = CompletedNotice.from(event.payload());
    notices.insertNotice(noticeId, listValues, 1, now);
    notices.insertVersion(versionRow(noticeId, 1, event, contentHash, List.of()), now);
    notices.upsertPost(
        event.rawPostId(),
        noticeId,
        source.kind(),
        source.supersedesPostId(),
        source.postedOn(),
        now);
    events.publishEvent(
        new NoticePublished(
            noticeId, event.field(), listValues.regionCode(), event.title(), event.reprocess()));
    return noticeId;
  }

  /** 지금 공개 버전과 비교해 달라진 섹션 (NoticeUpdated.changedFields). */
  private List<String> changedSections(CurrentVersion current, ExtractionEvent event) {
    return NoticeContent.changedSections(
        parse(current.snapshotJson()),
        parse(current.conditionsJson()),
        parse(current.heldItemsJson()),
        parse(current.applicationWindowsJson()),
        current.judgmentJson() == null ? null : parse(current.judgmentJson()),
        event);
  }

  private void publishVersion(
      CurrentVersion current,
      ExtractionEvent event,
      Source source,
      String contentHash,
      List<String> changed,
      Instant now) {
    int version = current.version() + 1;
    notices.updateNotice(current.noticeId(), CompletedNotice.from(event.payload()), version, now);
    notices.insertVersion(
        versionRow(current.noticeId(), version, event, contentHash, changed), now);
    notices.upsertPost(
        event.rawPostId(),
        current.noticeId(),
        source.kind(),
        source.supersedesPostId(),
        source.postedOn(),
        now);
    events.publishEvent(new NoticeUpdated(current.noticeId(), changed, event.reprocess()));
  }

  private VersionRow versionRow(
      UUID noticeId, int version, ExtractionEvent event, String contentHash, List<String> changed) {
    return new VersionRow(
        noticeId,
        version,
        event.rawPostId(),
        event.rawVersion(),
        event.eventId(),
        event.reviewStatus(),
        json.writeValueAsString(event.payload()),
        json.writeValueAsString(event.conditions()),
        json.writeValueAsString(event.heldItems()),
        json.writeValueAsString(event.applicationWindows()),
        contentHash,
        json.writeValueAsString(changed));
  }

  private void record(
      ExtractionEvent event, Source source, Decision decision, UUID noticeId, Instant now) {
    results.insert(
        event.eventId(),
        event.rawPostId(),
        event.rawVersion(),
        event.reviewStatus(),
        decision.outcome(),
        decision.reason(),
        noticeId,
        source.supersedesPostId(),
        json.writeValueAsString(event.payload()),
        now);
  }

  private static Applied logged(ExtractionEvent event, Applied applied) {
    log.info(
        "추출 결과 반영 eventId={} rawPostId={} rawVersion={} outcome={} reason={} noticeId={}",
        event.eventId(),
        event.rawPostId(),
        event.rawVersion(),
        applied.decision().outcome(),
        applied.decision().reason(),
        applied.noticeId());
    return applied;
  }

  private JsonNode parse(String body) {
    try {
      return json.readTree(body);
    } catch (RuntimeException e) {
      throw new ExtractionEvent.InvalidEventException("JSON으로 읽을 수 없어요");
    }
  }
}
