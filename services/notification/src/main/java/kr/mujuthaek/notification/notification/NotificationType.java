package kr.mujuthaek.notification.notification;

/**
 * 알림 종류 (notifications.type). api가 notification.requested의 payload.type으로 정해 보내요.
 *
 * <p>{@code digest}는 "묶음을 지원하는 채널(이메일)에서는 하루 두 번 묶어서 보내는 종류"예요. 웹 푸시처럼 묶지 않는 채널은 이 값을 무시해요.
 */
public enum NotificationType {
  NEW_NOTICE(true),
  NEW_SUPPORT(true),
  DEADLINE_D3(false),
  DEADLINE_D1(false),
  CORRECTED(false),
  FOLLOW_UP(false);

  private final boolean digest;

  NotificationType(boolean digest) {
    this.digest = digest;
  }

  public boolean digest() {
    return digest;
  }
}
