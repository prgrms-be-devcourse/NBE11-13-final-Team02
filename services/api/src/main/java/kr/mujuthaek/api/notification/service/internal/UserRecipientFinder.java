package kr.mujuthaek.api.notification.service.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.mujuthaek.api.user.service.NotificationPreference;
import kr.mujuthaek.api.user.service.UserNotificationQueryService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 알림 받을 회원을 user 모듈 조회 메서드로 찾아요 (관심 조건·관심 공고·알림 설정). 이메일은 아직 받지 않아서 넣지 않고(운영 채널은 웹 푸시), 웹 푸시 기기는
 * {@link NotificationRequests}가 붙여요.
 *
 * <p>설정 파일의 테스트 회원(app.notification.test-recipients)은 모든 알림에 덧붙여요. 기본값은 비어 있고 로컬 확인(e2e)에서만 채워요.
 */
@Component
class UserRecipientFinder implements RecipientFinder {

  private final UserNotificationQueryService users;
  private final Properties properties;

  UserRecipientFinder(UserNotificationQueryService users, Properties properties) {
    this.users = users;
    this.properties = properties;
  }

  @Override
  public List<Recipient> forNewBenefit(String field, String regionScope, List<String> regionCodes) {
    return recipients(users.newBenefitAudience(field, regionScope, regionCodes), "NEW_SUPPORT");
  }

  @Override
  public List<Recipient> forNewNotice(
      String field, List<String> regionCodes, boolean nationwide, String noticeType) {
    return recipients(
        users.newNoticeAudience(field, regionCodes, nationwide, noticeType), "NEW_NOTICE");
  }

  @Override
  public List<Recipient> forFavorite(String targetType, UUID targetId, String type) {
    return recipients(users.favoriteAudience(targetType, targetId), type);
  }

  @Override
  public Recipient forReporter(UUID userId) {
    NotificationPreference p =
        users.preferences(List.of(userId)).getOrDefault(userId, NotificationPreference.DEFAULT);
    return new Recipient(userId, null, p.quietEnabled(), p.enabled("REPORT_ANSWERED"));
  }

  private List<Recipient> recipients(List<UUID> audience, String type) {
    Map<UUID, NotificationPreference> prefs = users.preferences(audience);
    List<Recipient> out = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (UUID id : audience) {
      NotificationPreference p = prefs.getOrDefault(id, NotificationPreference.DEFAULT);
      if (seen.add(id)) {
        out.add(new Recipient(id, null, p.quietEnabled(), p.enabled(type)));
      }
    }
    for (TestRecipient t : properties.testRecipients()) {
      if (seen.add(t.userId())) {
        out.add(new Recipient(t.userId(), t.email(), t.quietEnabled(), true));
      }
    }
    return out;
  }

  /** 설정 파일의 테스트 회원 한 명 (모든 종류 켬) */
  record TestRecipient(UUID userId, String email, boolean quietEnabled) {}

  @ConfigurationProperties("app.notification")
  record Properties(@DefaultValue List<TestRecipient> testRecipients) {}

  @Configuration
  @EnableConfigurationProperties(Properties.class)
  static class Config {}
}
