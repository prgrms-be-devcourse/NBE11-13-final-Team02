package kr.mujuthaek.notification.settings;

import kr.mujuthaek.notification.notification.NotificationType;

/** 회원 알림 설정 한 줄 (notification_settings). 채널 켜기·끄기와 종류별 켜기·끄기, 조용한 시간이에요. */
public record NotificationSettings(
    boolean emailEnabled,
    boolean pushEnabled,
    boolean newNotice,
    boolean newSupport,
    boolean deadline,
    boolean correction,
    boolean followUp,
    boolean quietEnabled) {

  /** 이 종류의 알림을 받겠다고 했나 (채널과 상관없이) */
  public boolean wants(NotificationType type) {
    return switch (type) {
      case NEW_NOTICE -> newNotice;
      case NEW_SUPPORT -> newSupport;
      case DEADLINE_D3, DEADLINE_D1 -> deadline;
      case CORRECTED -> correction;
      case FOLLOW_UP -> followUp;
    };
  }
}
