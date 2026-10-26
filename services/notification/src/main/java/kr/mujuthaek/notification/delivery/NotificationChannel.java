package kr.mujuthaek.notification.delivery;

import java.util.List;
import java.util.Optional;
import kr.mujuthaek.notification.notification.NotificationRequest;

/**
 * 발송 채널 하나 (이메일, 웹 푸시 …). 채널마다 다른 일은 여기에만 있어요.
 *
 * <p>발송 줄 만들기·중복 거르기·보낼 시각·재시도·결과 기록은 채널과 상관없이 {@code NotificationService}와 {@link DeliveryWorker}가
 * 해요. 새 채널은 이 인터페이스를 구현한 빈을 하나 추가하면 그대로 붙어요.
 */
public interface NotificationChannel {

  Channel channel();

  /**
   * 요청에 담긴 이 채널의 받을 곳. 없으면 발송 줄을 만들지 않아요. 이메일은 주소, 웹 푸시는 그 회원의 구독 기기 목록(JSON)이에요.
   *
   * <p>받을지 말지는 api가 정해서 넣거나 빼요. 채널은 요청에서 꺼내기만 해요. 회원 1명·채널 1개에 발송 줄 1개라, 기기가 여러 대인 푸시는 {@link
   * #send}에서 기기마다 보내요.
   */
  Optional<String> address(NotificationRequest.Recipient recipient);

  /** 묶음 종류(NotificationType.digest)를 하루 두 번 묶어 보내나. 이메일은 묶고, 웹 푸시는 바로 보내요 */
  boolean supportsDigest();

  /**
   * {@link #address}가 준 곳에 보내요. 묶음이면 여러 건, 아니면 1건이에요.
   *
   * @return 발송 서비스가 준 ID (SES MessageId 등). 반송 알림과 맞출 때 써요
   */
  String send(String address, List<OutgoingNotification> notifications) throws DeliveryException;
}
