package kr.mujuthaek.notification.delivery;

/**
 * 채널이 보내지 못했을 때. {@code permanent}면 다시 보내도 안 되는 실패라 바로 FAILED, 아니면 뒤로 미뤄 다시 보내요.
 *
 * <p>{@code code}는 deliveries.last_error에 남아요. 이메일 주소·푸시 주소 같은 개인 정보는 넣지 않아요.
 */
public class DeliveryException extends Exception {

  private final String code;
  private final boolean permanent;

  public DeliveryException(String code, boolean permanent, Throwable cause) {
    super(code, cause);
    this.code = code;
    this.permanent = permanent;
  }

  public DeliveryException(String code, boolean permanent) {
    this(code, permanent, null);
  }

  public String code() {
    return code;
  }

  public boolean permanent() {
    return permanent;
  }
}
