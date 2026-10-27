package kr.mujuthaek.api.notification.service.internal;

import java.time.Clock;
import java.time.Duration;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notification.client.NotificationServerClient;
import kr.mujuthaek.api.notification.dto.response.AdminNotificationStatsResponse;
import kr.mujuthaek.api.notification.repository.InboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 「알림」 화면: 알림 서버의 발송 결과 + 알림함의 봤는지·눌렀는지. 알림함은 90일만 남아서 90일까지 봐요. */
@Service
public class NotificationStatsService {

  static final int MAX_DAYS = 90;

  private final InboxRepository inbox;
  private final NotificationServerClient server;
  private final Clock clock;

  public NotificationStatsService(
      InboxRepository inbox, NotificationServerClient server, Clock clock) {
    this.inbox = inbox;
    this.server = server;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AdminNotificationStatsResponse stats(int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "days는 1~" + MAX_DAYS + "이에요");
    }
    var engagement =
        inbox.engagement(clock.instant().minus(Duration.ofDays(days))).stream()
            .map(
                e ->
                    new AdminNotificationStatsResponse.Engagement(
                        e.type(), e.created(), e.shown(), e.read(), e.clicked()))
            .toList();
    return new AdminNotificationStatsResponse(
        days, server.deliveryStats(days).orElse(null), engagement);
  }
}
