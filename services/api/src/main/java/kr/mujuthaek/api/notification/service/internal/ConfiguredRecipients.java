package kr.mujuthaek.api.notification.service.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 관심 조건 테이블이 생기기 전까지 쓰는 받는 사람: 설정 파일(app.notification.test-recipients)의 회원을 모든 알림에 넣어요. 기본값은 비어 있어서
 * 아무에게도 보내지 않아요. 로컬에서만 채워요.
 */
@Component
class ConfiguredRecipients implements RecipientFinder {

  private final Properties properties;

  ConfiguredRecipients(Properties properties) {
    this.properties = properties;
  }

  @Override
  public List<Recipient> forNewBenefit(String field, String regionScope, List<String> regionCodes) {
    return recipients();
  }

  @Override
  public List<Recipient> forNewNotice(String field, List<String> regionCodes, boolean nationwide) {
    return recipients();
  }

  @Override
  public List<Recipient> forFavorite(String targetType, UUID targetId) {
    return recipients();
  }

  private List<Recipient> recipients() {
    return properties.testRecipients().stream()
        .map(t -> new Recipient(t.userId(), t.email(), t.quietEnabled(), true))
        .toList();
  }

  /** 설정 파일의 테스트 회원 한 명 (모든 종류 켬) */
  record TestRecipient(UUID userId, String email, boolean quietEnabled) {}

  @ConfigurationProperties("app.notification")
  record Properties(@DefaultValue List<TestRecipient> testRecipients) {}

  @Configuration
  @EnableConfigurationProperties(Properties.class)
  static class Config {}
}
