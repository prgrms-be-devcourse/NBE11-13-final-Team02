package kr.mujuthaek.api.benefit.service.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import kr.mujuthaek.api.benefit.domain.BenefitCategory;
import kr.mujuthaek.api.benefit.domain.BenefitStatus;
import kr.mujuthaek.api.benefit.mapper.PolicyExtractedEvent;
import kr.mujuthaek.api.benefit.repository.BenefitRepository;
import kr.mujuthaek.api.common.event.BenefitPublished;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * policy.extracted를 혜택으로 저장해요 (policy-extracted.draft.md 4장 "저장 규칙"). 처음 받은 정책은 추가, 더 큰 수집 버전은 고치고,
 * 같거나 작은 버전은 버려요. 처음 저장한 범위 안 정책은 BenefitPublished로 알려요 (재처리는 알리지 않아요).
 *
 * <p>이번엔 주거 혜택(BENEFIT)만 받아요. 창업지원은 notice.policy_judgments에 있어요.
 */
@Service
public class BenefitIngestionService {

  private static final Logger log = LoggerFactory.getLogger(BenefitIngestionService.class);
  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

  private final BenefitRepository benefits;
  private final ApplicationEventPublisher events;
  private final JsonMapper json;
  private final Clock clock;

  public BenefitIngestionService(
      BenefitRepository benefits, ApplicationEventPublisher events, JsonMapper json, Clock clock) {
    this.benefits = benefits;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  /** 받은 봉투 글자 그대로. 계약에 맞지 않으면 PolicyExtractedEvent.InvalidEventException. */
  @Transactional
  public void apply(String envelope) {
    PolicyExtractedEvent e = PolicyExtractedEvent.from(json.readTree(envelope));
    if (!"BENEFIT".equals(e.field())) {
      log.info("주거 혜택이 아니라 저장하지 않아요 field={} rawPolicyId={}", e.field(), e.rawPolicyId());
      return;
    }
    var saved = benefits.findByRawPolicyId(e.rawPolicyId());
    if (saved.isPresent() && saved.get().version() >= e.version()) {
      log.info(
          "이미 같거나 새 버전이 있어 버려요 rawPolicyId={} version={} saved={}",
          e.rawPolicyId(),
          e.version(),
          saved.get().version());
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(KOREA));
    BenefitStatus status =
        BenefitStatus.of(e.relevant(), e.applyAlways(), e.applyStart(), e.applyEnd(), today);
    String category =
        BenefitCategory.of(
                e.supports().stream().map(PolicyExtractedEvent.Support::kind).toList(),
                e.categories())
            .name();
    var id =
        benefits.save(saved.map(s -> s.id()).orElse(null), e, category, status, clock.instant());
    log.info(
        "혜택 반영 eventId={} rawPolicyId={} version={} benefitId={} status={}",
        e.eventId(),
        e.rawPolicyId(),
        e.version(),
        id,
        status);
    if (saved.isEmpty() && e.relevant() && !e.reprocess()) {
      events.publishEvent(
          new BenefitPublished(
              id,
              e.field(),
              e.regionCodes().isEmpty() ? null : e.regionCodes().getFirst(),
              e.title(),
              false));
    }
  }
}
