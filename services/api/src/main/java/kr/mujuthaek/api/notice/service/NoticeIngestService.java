package kr.mujuthaek.api.notice.service;

import java.util.UUID;
import kr.mujuthaek.api.common.id.UuidV7;
import kr.mujuthaek.api.notice.domain.CompletedNotice;
import kr.mujuthaek.api.notice.repository.NoticeStore;
import kr.mujuthaek.api.notice.repository.NoticeStore.NoticeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * extraction.completed 한 건을 공고 버전으로 저장해요.
 *
 * <ul>
 *   <li>공고 찾기: 이미 받은 원본(이전 버전 포함)이 붙은 공고 → 정정·후속이면 원래 공고(supersedesPostId) → 없으면 새 공고
 *   <li>검수 대기(IN_REVIEW)는 저장만 하고 지금 공개된 버전을 그대로 둬요
 *   <li>후속 안내(FOLLOW_UP)는 원래 공고에 붙여 두기만 해요. 모집 내용을 바꾸지 않아서 공개 버전으로 쓰지 않아요
 *   <li>더 새로운 원본이면 공고 버전을 하나 올려 공개해요. 같은 원본 버전을 다시 받으면(검수 승인 재발행) 내용만 바꾸고 버전은 그대로예요
 *   <li>같은 원본의 예전 버전이 늦게 오면 저장만 하고 공개 버전은 바꾸지 않아요
 * </ul>
 */
@Service
public class NoticeIngestService {

  private static final Logger log = LoggerFactory.getLogger(NoticeIngestService.class);

  /** 처리 결과 (테스트·로그용). */
  public enum Outcome {
    IGNORED_FIELD,
    STORED_ONLY,
    PUBLISHED,
    NEW_VERSION,
    REFRESHED
  }

  private final NoticeStore store;

  public NoticeIngestService(NoticeStore store) {
    this.store = store;
  }

  @Transactional
  public Outcome accept(UUID eventId, JsonNode payload) {
    CompletedNotice n = CompletedNotice.from(payload);
    if (!n.isNoticeField()) {
      return Outcome.IGNORED_FIELD;
    }
    UUID noticeId = findOrCreate(n);
    store.upsertSource(noticeId, eventId, n);
    NoticeRow current = store.find(noticeId).orElseThrow();
    Outcome outcome = decide(n, current);
    switch (outcome) {
      case PUBLISHED, NEW_VERSION -> store.publish(noticeId, n, current.currentVersion() + 1);
      case REFRESHED -> store.publish(noticeId, n, current.currentVersion());
      default -> {}
    }
    log.info(
        "공고 저장 {} v{} {} {}: {}",
        n.rawPostId(),
        n.rawVersion(),
        n.kind(),
        n.reviewStatus(),
        outcome);
    return outcome;
  }

  private UUID findOrCreate(CompletedNotice n) {
    var own = store.noticeIdOf(n.rawPostId());
    if (own.isPresent()) {
      return own.get();
    }
    if (n.supersedesPostId() != null) {
      var original = store.noticeIdOf(n.supersedesPostId());
      if (original.isPresent()) {
        return original.get();
      }
    }
    UUID id = UuidV7.next();
    store.insertNotice(id, n);
    return id;
  }

  static Outcome decide(CompletedNotice n, NoticeRow current) {
    if (!n.publishable() || "FOLLOW_UP".equals(n.kind())) {
      return Outcome.STORED_ONLY;
    }
    if (current.currentVersion() == 0) {
      return Outcome.PUBLISHED;
    }
    boolean sameSource = n.rawPostId().equals(current.currentRawPostId());
    if (sameSource && n.rawVersion() == current.currentRawVersion()) {
      return Outcome.REFRESHED;
    }
    if (sameSource && n.rawVersion() < current.currentRawVersion()) {
      return Outcome.STORED_ONLY; // 예전 버전이 늦게 옴
    }
    return Outcome.NEW_VERSION;
  }
}
