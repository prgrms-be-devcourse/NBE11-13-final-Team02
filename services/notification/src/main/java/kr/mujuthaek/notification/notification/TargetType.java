package kr.mujuthaek.notification.notification;

/** 알림이 가리키는 대상이 들어 있는 api 테이블 (notifications.target_type). */
public enum TargetType {
  NOTICE("공고"), // notices
  BENEFIT("지원 사업"), // benefits
  SPACE_RECRUITMENT("사업공간 모집"); // space_recruitments

  private final String label;

  TargetType(String label) {
    this.label = label;
  }

  /** 알림 문구에 쓰는 이름 */
  public String label() {
    return label;
  }
}
