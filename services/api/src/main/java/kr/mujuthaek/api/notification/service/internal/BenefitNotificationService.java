package kr.mujuthaek.api.notification.service.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.benefit.service.BenefitSummary;
import kr.mujuthaek.api.common.event.BenefitPublished;
import kr.mujuthaek.api.common.event.BenefitUpdated;
import kr.mujuthaek.api.common.messaging.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * benefit의 내부 이벤트(BenefitPublished·BenefitUpdated)를 알림 서버로 보낼 notification.requested로 바꿔 아웃박스에 적어요
 * (contracts/schemas/notification-events.draft.md). 저장과 같은 트랜잭션에서 불러요.
 *
 * <ul>
 *   <li>BenefitPublished → NEW_SUPPORT (관심 조건이 맞는 회원)
 *   <li>BenefitUpdated → CORRECTED (관심 공고로 둔 회원). 알릴 부분이 바뀌었을 때만
 * </ul>
 *
 * <p>과거 자료 재처리(reprocess), 숨긴 정책, 이미 마감된 정책의 새 소식은 보내지 않아요. 받는 사람은 한 이벤트에 500명까지라 넘으면 나눠 보내요.
 */
@Service
public class BenefitNotificationService {

  private static final Logger log = LoggerFactory.getLogger(BenefitNotificationService.class);

  static final String EVENT_TYPE = "notification.requested";
  static final int MAX_RECIPIENTS = 500;

  /** 정정 알림에 넣을 부분과 문구 이름 (BenefitUpdated.changedFields). 기본 정보·지역은 알리지 않아요 */
  static final Map<String, String> ALERT_LABELS = labels();

  private final BenefitQueryService benefits;
  private final RecipientFinder recipients;
  private final Outbox outbox;
  private final Clock clock;

  public BenefitNotificationService(
      BenefitQueryService benefits, RecipientFinder recipients, Outbox outbox, Clock clock) {
    this.benefits = benefits;
    this.recipients = recipients;
    this.outbox = outbox;
    this.clock = clock;
  }

  /** 새 정책 → NEW_SUPPORT. 보낸 요청 수를 돌려줘요 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int onPublished(BenefitPublished event) {
    if (event.reprocess()) {
      return 0;
    }
    Optional<BenefitSummary> found = visible(event.benefitId());
    if (found.isEmpty()) {
      return 0;
    }
    BenefitSummary b = found.get();
    if ("CLOSED".equals(b.status())) {
      return 0; // 이미 마감된 정책은 새 소식으로 알리지 않아요
    }
    String kind = kind(b.field());
    return request(
        "NEW_SUPPORT",
        targetType(b.field()) + ":" + b.benefitId() + ":NEW",
        b,
        "새 " + kind + ": " + b.title(),
        "관심 조건에 맞는 새 " + kind + " 소식이에요." + summary(b),
        recipients.forNewBenefit(b.field(), b.regionScope(), b.regionCodes()));
  }

  /** 바뀐 정책 → CORRECTED. 알릴 부분이 없으면 보내지 않아요 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int onUpdated(BenefitUpdated event) {
    if (event.reprocess()) {
      return 0;
    }
    String labels =
        event.changedFields().stream()
            .map(ALERT_LABELS::get)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.joining(", "));
    if (labels.isEmpty()) {
      return 0;
    }
    Optional<BenefitSummary> found = visible(event.benefitId());
    if (found.isEmpty()) {
      return 0;
    }
    BenefitSummary b = found.get();
    String type = targetType(b.field());
    return request(
        "CORRECTED",
        type + ":" + b.benefitId() + ":CORRECTED:" + event.version(),
        b,
        "정정: " + b.title(),
        "관심 " + kind(b.field()) + "의 " + labels + " 내용이 바뀌었어요.",
        recipients.forFavorite(type, b.benefitId()));
  }

  private Optional<BenefitSummary> visible(UUID id) {
    return benefits.findSummary(id).filter(b -> !"HIDDEN".equals(b.status()));
  }

  private int request(
      String type,
      String dedupKey,
      BenefitSummary b,
      String title,
      String body,
      List<Recipient> to) {
    if (to.isEmpty()) {
      log.debug("받을 회원이 없어 알림 요청을 만들지 않아요: type={}, benefitId={}", type, b.benefitId());
      return 0;
    }
    String targetType = targetType(b.field());
    int sent = 0;
    for (int from = 0; from < to.size(); from += MAX_RECIPIENTS) {
      List<Recipient> chunk = to.subList(from, Math.min(from + MAX_RECIPIENTS, to.size()));
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("type", type);
      payload.put("dedupKey", dedupKey);
      payload.put("target", Map.of("type", targetType, "id", b.benefitId()));
      payload.put("title", title);
      payload.put("body", body);
      payload.put("linkPath", linkPath(targetType, b.benefitId()));
      payload.put("recipients", chunk.stream().map(BenefitNotificationService::recipient).toList());
      outbox.add(EVENT_TYPE, 1, payload);
      sent++;
    }
    log.info(
        "알림 요청을 아웃박스에 적음: type={}, benefitId={}, 받는 사람={}, 요청={}",
        type,
        b.benefitId(),
        to.size(),
        sent);
    return sent;
  }

  private static Map<String, Object> recipient(Recipient r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("userId", r.userId());
    m.put("email", r.email());
    m.put("quietEnabled", r.quietEnabled());
    return m;
  }

  /** 계약의 target.type: 사업공간 모집은 space_recruitments, 나머지는 benefits */
  static String targetType(String field) {
    return "SPACE_RECRUITMENT".equals(field) ? "SPACE_RECRUITMENT" : "BENEFIT";
  }

  static String linkPath(String targetType, UUID id) {
    return ("SPACE_RECRUITMENT".equals(targetType) ? "/space-recruitments/" : "/benefits/") + id;
  }

  static String kind(String field) {
    return switch (field) {
      case "STARTUP_SUPPORT" -> "창업지원";
      case "SPACE_RECRUITMENT" -> "사업공간 모집";
      default -> "지원 정책";
    };
  }

  private String summary(BenefitSummary b) {
    StringBuilder sb = new StringBuilder();
    if (b.agency() != null && !b.agency().isBlank()) {
      sb.append(' ').append(b.agency());
    }
    if (b.applyAlways()) {
      sb.append(sb.isEmpty() ? " " : " · ").append("상시 모집");
    } else if (b.applyEnd() != null && !b.applyEnd().isBefore(LocalDate.now(clock))) {
      sb.append(sb.isEmpty() ? " " : " · ")
          .append(b.applyEnd().getMonthValue())
          .append("월 ")
          .append(b.applyEnd().getDayOfMonth())
          .append("일 마감");
    }
    return sb.toString();
  }

  private static Map<String, String> labels() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("schedule", "일정");
    m.put("conditions", "자격");
    m.put("supports", "지원 내용");
    m.put("units", "공간·임대료");
    m.put("recruitCount", "모집 수");
    m.put("residency", "입주 기간");
    m.put("hqRelocation", "본점 이전 조건");
    m.put("facility", "시설 정보");
    m.put("support", "입주 지원");
    m.put("application", "신청 방법");
    return java.util.Collections.unmodifiableMap(m);
  }
}
