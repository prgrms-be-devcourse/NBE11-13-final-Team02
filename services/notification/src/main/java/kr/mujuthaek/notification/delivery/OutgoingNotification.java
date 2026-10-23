package kr.mujuthaek.notification.delivery;

import java.util.UUID;
import kr.mujuthaek.notification.notification.NotificationType;

/** 채널에 넘기는 알림 1건. 채널은 이 값으로 메일·푸시 모양을 만들어요. */
public record OutgoingNotification(
    UUID notificationId, NotificationType type, String title, String body, String linkPath) {}
