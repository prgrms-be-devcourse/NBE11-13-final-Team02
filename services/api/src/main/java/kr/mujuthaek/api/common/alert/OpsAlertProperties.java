package kr.mujuthaek.api.common.alert;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 슬랙 알림 설정.
 *
 * @param webhookUrl 슬랙 Incoming Webhook 주소({@value OpsAlert#WEBHOOK_ENV}, 추출 서비스 비용 알림과 같은 값). 채널은
 *     웹훅에 묶여 있어요. 비면 보내지 않고 로그만 남겨요
 * @param environment 메시지 머리에 붙일 환경 이름 (prod · dev · local)
 * @param quiet 같은 종류 알림을 다시 보내지 않는 시간. 그동안 생긴 횟수는 다음 알림에 붙여요
 */
@ConfigurationProperties("mujuthaek.ops-alert")
public record OpsAlertProperties(String webhookUrl, String environment, Duration quiet) {

  public OpsAlertProperties {
    environment = environment == null || environment.isBlank() ? "local" : environment;
    quiet = quiet == null ? Duration.ofMinutes(10) : quiet;
  }

  public boolean enabled() {
    return webhookUrl != null && !webhookUrl.isBlank();
  }

  @Override
  public String toString() {
    // 웹훅 주소는 그 자체가 비밀이라 남기지 않아요
    return "OpsAlertProperties[enabled=" + enabled() + ", environment=" + environment + "]";
  }
}
