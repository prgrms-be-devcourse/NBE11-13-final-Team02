package kr.mujuthaek.api.notification.service.internal;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.common.messaging.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * notification.requested를 아웃박스에 적어요 (contracts/schemas/notification-events.draft.md). 받는 사람은 한 요청에
 * 500명까지라 넘으면 나눠 적어요. 부르는 쪽의 트랜잭션 안에서 써요.
 */
final class NotificationRequests {

  private static final Logger log = LoggerFactory.getLogger(NotificationRequests.class);

  static final String EVENT_TYPE = "notification.requested";
  static final int MAX_RECIPIENTS = 500;

  private final Outbox outbox;

  NotificationRequests(Outbox outbox) {
    this.outbox = outbox;
  }

  /** 적은 요청 수를 돌려줘요. 받는 사람이 없으면 0 */
  int write(
      String type,
      String dedupKey,
      String targetType,
      UUID targetId,
      String title,
      String body,
      List<Recipient> to) {
    if (to.isEmpty()) {
      log.debug("받을 회원이 없어 알림 요청을 만들지 않아요: type={}, targetId={}", type, targetId);
      return 0;
    }
    int sent = 0;
    for (int from = 0; from < to.size(); from += MAX_RECIPIENTS) {
      List<Recipient> chunk = to.subList(from, Math.min(from + MAX_RECIPIENTS, to.size()));
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("type", type);
      payload.put("dedupKey", dedupKey);
      payload.put("target", Map.of("type", targetType, "id", targetId));
      payload.put("title", title);
      payload.put("body", body);
      payload.put("linkPath", linkPath(targetType, targetId));
      payload.put("recipients", chunk.stream().map(NotificationRequests::recipient).toList());
      outbox.add(EVENT_TYPE, 1, payload);
      sent++;
    }
    log.info(
        "알림 요청을 아웃박스에 적음: type={}, targetId={}, 받는 사람={}, 요청={}", type, targetId, to.size(), sent);
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
    return switch (targetType) {
          case "NOTICE" -> "/notices/";
          case "SPACE_RECRUITMENT" -> "/space-recruitments/";
          default -> "/benefits/";
        }
        + id;
  }

  /** 알림 문구의 {종류} */
  static String kind(String field) {
    return switch (field) {
      case "STARTUP_SUPPORT" -> "창업지원";
      case "SPACE_RECRUITMENT" -> "사업공간 모집";
      default -> "지원 정책";
    };
  }

  /** 문구의 날짜 (M월 d일) */
  static String monthDay(LocalDate date) {
    return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
  }
}
