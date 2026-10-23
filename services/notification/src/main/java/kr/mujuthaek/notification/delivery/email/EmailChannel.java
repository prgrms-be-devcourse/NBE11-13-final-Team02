package kr.mujuthaek.notification.delivery.email;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.notification.delivery.Channel;
import kr.mujuthaek.notification.delivery.DeliveryException;
import kr.mujuthaek.notification.delivery.NotificationChannel;
import kr.mujuthaek.notification.delivery.OutgoingNotification;
import kr.mujuthaek.notification.settings.NotificationSettings;
import org.springframework.stereotype.Component;

/** 이메일 채널. 받을 곳은 email_addresses의 인증된 주소이고, 새 공고·새 정책은 하루 두 번 묶어 1통으로 보내요. */
@Component
class EmailChannel implements NotificationChannel {

  private final EmailAddressRepository addresses;
  private final EmailSender sender;
  private final MailProperties properties;

  EmailChannel(EmailAddressRepository addresses, EmailSender sender, MailProperties properties) {
    this.addresses = addresses;
    this.sender = sender;
    this.properties = properties;
  }

  @Override
  public Channel channel() {
    return Channel.EMAIL;
  }

  @Override
  public boolean canDeliver(UUID userId, NotificationSettings settings) {
    return settings.emailEnabled() && addresses.findDeliverable(userId).isPresent();
  }

  @Override
  public boolean supportsDigest() {
    return true;
  }

  @Override
  public String send(UUID userId, List<OutgoingNotification> notifications)
      throws DeliveryException {
    String to =
        addresses
            .findDeliverable(userId)
            .orElseThrow(() -> new DeliveryException("NO_ADDRESS", true));
    return sender.send(new EmailMessage(to, subject(notifications), text(notifications)));
  }

  private static String subject(List<OutgoingNotification> notifications) {
    return notifications.size() == 1
        ? "[무주택자들] " + notifications.getFirst().title()
        : "[무주택자들] 관심 조건에 맞는 새 소식 " + notifications.size() + "건";
  }

  private String text(List<OutgoingNotification> notifications) {
    StringBuilder sb = new StringBuilder();
    for (OutgoingNotification n : notifications) {
      sb.append("■ ").append(n.title()).append('\n');
      sb.append(n.body()).append('\n');
      sb.append(properties.webBaseUrl()).append(n.linkPath()).append("\n\n");
    }
    sb.append("---\n");
    sb.append("알림 설정은 여기서 바꿀 수 있어요: ")
        .append(properties.webBaseUrl())
        .append("/settings/notifications\n");
    return sb.toString();
  }
}
