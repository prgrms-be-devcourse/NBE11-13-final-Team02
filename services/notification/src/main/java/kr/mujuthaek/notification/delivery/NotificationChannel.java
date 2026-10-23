package kr.mujuthaek.notification.delivery;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.notification.settings.NotificationSettings;

/**
 * 발송 채널 하나 (이메일, 웹 푸시 …). 채널마다 다른 일은 여기에만 있어요.
 *
 * <p>알림 만들기·중복 거르기·보낼 시각·재시도·결과 기록은 채널과 상관없이 {@code NotificationService}와 {@link DeliveryWorker}가
 * 해요. 새 채널은 이 인터페이스를 구현한 빈을 하나 추가하면 발송 줄 만들기부터 재시도까지 그대로 붙어요.
 */
public interface NotificationChannel {

  Channel channel();

  /** 이 회원에게 이 채널로 보낼 수 있나. 채널을 켰고 받을 곳(인증된 이메일, 푸시 구독)이 있어야 해요 */
  boolean canDeliver(UUID userId, NotificationSettings settings);

  /** 묶음 종류(NotificationType.digest)를 하루 두 번 묶어 보내나. 이메일은 묶고, 웹 푸시는 바로 보내요 */
  boolean supportsDigest();

  /**
   * 한 회원에게 보내요. 묶음이면 여러 건, 아니면 1건이에요.
   *
   * @return 발송 서비스가 준 ID (SES MessageId 등). 반송 알림과 맞출 때 써요
   */
  String send(UUID userId, List<OutgoingNotification> notifications) throws DeliveryException;
}
