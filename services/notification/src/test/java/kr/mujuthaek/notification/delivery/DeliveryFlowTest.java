package kr.mujuthaek.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujuthaek.notification.TestcontainersConfiguration;
import kr.mujuthaek.notification.delivery.email.EmailMessage;
import kr.mujuthaek.notification.delivery.email.EmailSender;
import kr.mujuthaek.notification.notification.NotificationRequest;
import kr.mujuthaek.notification.notification.NotificationService;
import kr.mujuthaek.notification.notification.NotificationType;
import kr.mujuthaek.notification.notification.TargetType;
import kr.mujuthaek.notification.settings.NotificationSettings;
import kr.mujuthaek.notification.support.TimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 요청 → 발송 줄 → 워커 → 채널까지. 이메일은 실제로 보내지 않고 기록만 하는 가짜, 웹 푸시는 테스트용 채널로 끼워서 채널을 추가해도 같은 흐름으로 도는지
 * 확인해요.
 */
@SpringBootTest(properties = "notification.delivery.poll-enabled=false")
@Import({TestcontainersConfiguration.class, DeliveryFlowTest.Fakes.class})
class DeliveryFlowTest {

  static final UUID USER = UUID.fromString("0192b6f0-0000-7000-8000-0000000000a1");

  @Autowired NotificationService service;
  @Autowired DeliveryWorker worker;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired MutableClock clock;
  @Autowired RecordingEmailSender email;
  @Autowired FakePushChannel push;

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from notifications").update();
    jdbc.sql("delete from notification_settings").update();
    jdbc.sql("delete from email_addresses").update();
    email.sent.clear();
    email.failure.set(null);
    push.sent.clear();
    clock.set("2026-10-23T10:00");
    jdbc.sql(
            """
            insert into notification_settings (user_id, email_enabled) values (:u, true);
            insert into email_addresses (user_id, email, consent_version, consented_at, verified_at,
                                         unsubscribe_token_hash)
            values (:u, 'me@example.com', 'v1', now(), now(), 'hash-a1')
            """)
        .param("u", USER)
        .update();
  }

  @Test
  void immediateNotificationIsSentOnce() {
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", List.of(USER));
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", List.of(USER)); // 같은 알림 다시

    worker.runOnce();

    assertThat(email.sent).hasSize(1);
    assertThat(email.sent.getFirst().to()).isEqualTo("me@example.com");
    assertThat(email.sent.getFirst().subject()).isEqualTo("[무주택자들] 정정: 강동구 행복주택");
    assertThat(status(Channel.EMAIL)).containsExactly("SENT");
  }

  @Test
  void userWithoutSettingsOnlyGetsInboxEntry() {
    UUID other = UUID.randomUUID();
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", List.of(other));

    assertThat(jdbc.sql("select count(*) from notifications where user_id = :u").param("u", other))
        .extracting(s -> s.query(Long.class).single())
        .isEqualTo(1L);
    assertThat(jdbc.sql("select count(*) from deliveries").query(Long.class).single()).isZero();
  }

  @Test
  void newNoticesAreBundledIntoOneEmailAtSixPm() {
    request(NotificationType.NEW_NOTICE, "NOTICE:A:NEW", List.of(USER));
    request(NotificationType.NEW_NOTICE, "NOTICE:B:NEW", List.of(USER));

    worker.runOnce();
    assertThat(email.sent).isEmpty(); // 10시: 아직 묶음 시각 전

    clock.set("2026-10-23T18:00");
    worker.runOnce();
    assertThat(email.sent).hasSize(1);
    assertThat(email.sent.getFirst().subject()).isEqualTo("[무주택자들] 관심 조건에 맞는 새 소식 2건");
    assertThat(status(Channel.EMAIL)).containsExactly("SENT", "SENT");
  }

  @Test
  void quietHoursPostponeUntilEight() {
    jdbc.sql("update notification_settings set quiet_enabled = true").update();
    clock.set("2026-10-23T23:00");
    request(NotificationType.DEADLINE_D1, "NOTICE:X:D1:2026-10-24", List.of(USER));

    worker.runOnce();
    assertThat(email.sent).isEmpty();

    clock.set("2026-10-24T08:00");
    worker.runOnce();
    assertThat(email.sent).hasSize(1);
  }

  @Test
  void temporaryFailureIsRetriedAndPermanentFailureStops() {
    email.failure.set(new DeliveryException("SES_TooManyRequests", false));
    request(NotificationType.DEADLINE_D3, "NOTICE:X:D3:2026-10-26", List.of(USER));

    worker.runOnce();
    assertThat(status(Channel.EMAIL)).containsExactly("PENDING");

    clock.set("2026-10-23T10:01");
    email.failure.set(new DeliveryException("SES_MessageRejected", true));
    worker.runOnce();
    assertThat(status(Channel.EMAIL)).containsExactly("FAILED");
    assertThat(jdbc.sql("select attempts from deliveries").query(Integer.class).single())
        .isEqualTo(2);
  }

  @Test
  void turningEmailOffBeforeSendingSkipsIt() {
    request(NotificationType.FOLLOW_UP, "NOTICE:X:FOLLOW_UP:P1", List.of(USER));
    jdbc.sql("update notification_settings set email_enabled = false").update();

    worker.runOnce();

    assertThat(email.sent).isEmpty();
    assertThat(status(Channel.EMAIL)).containsExactly("SKIPPED");
  }

  @Test
  void addedChannelGetsItsOwnDeliveryWithoutDigest() {
    jdbc.sql("update notification_settings set push_enabled = true").update();
    request(NotificationType.NEW_NOTICE, "NOTICE:A:NEW", List.of(USER));

    worker.runOnce();

    assertThat(push.sent).hasSize(1); // 푸시는 묶지 않아서 바로
    assertThat(email.sent).isEmpty(); // 이메일은 18시 묶음
    assertThat(status(Channel.PUSH)).containsExactly("SENT");
  }

  private void request(NotificationType type, String dedupKey, List<UUID> userIds) {
    UUID targetId = UUID.randomUUID();
    NotificationRequest request =
        new NotificationRequest(
            type,
            dedupKey,
            userIds,
            new NotificationRequest.Target(
                TargetType.NOTICE,
                targetId,
                "강동구 행복주택",
                "SH",
                LocalDate.of(2026, 11, 6),
                "/notices/" + targetId),
            type == NotificationType.CORRECTED
                ? new NotificationRequest.Correction(List.of("schedules"))
                : null,
            type == NotificationType.FOLLOW_UP
                ? new NotificationRequest.FollowUp("당첨자 발표", LocalDate.of(2026, 12, 1))
                : null);
    tx.executeWithoutResult(s -> service.handle(request));
  }

  private List<String> status(Channel channel) {
    return jdbc.sql("select status from deliveries where channel = :c order by status")
        .param("c", channel.name())
        .query(String.class)
        .list();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }

    @Bean
    FakePushChannel fakePushChannel() {
      return new FakePushChannel();
    }
  }

  static class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    void set(String seoulLocalDateTime) {
      now.set(ZonedDateTime.parse(seoulLocalDateTime + ":00+09:00[Asia/Seoul]").toInstant());
    }

    @Override
    public ZoneId getZone() {
      return TimeConfig.SEOUL;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now.get(), zone);
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  static class RecordingEmailSender implements EmailSender {
    final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    final AtomicReference<DeliveryException> failure = new AtomicReference<>();

    @Override
    public String send(EmailMessage message) throws DeliveryException {
      if (failure.get() != null) {
        throw failure.get();
      }
      sent.add(message);
      return "fake-" + sent.size();
    }
  }

  /** 웹 푸시 자리. 실제 PushChannel도 이 모양으로 구현하면 돼요 */
  static class FakePushChannel implements NotificationChannel {
    final List<OutgoingNotification> sent = new CopyOnWriteArrayList<>();

    @Override
    public Channel channel() {
      return Channel.PUSH;
    }

    @Override
    public boolean canDeliver(UUID userId, NotificationSettings settings) {
      return settings.pushEnabled();
    }

    @Override
    public boolean supportsDigest() {
      return false;
    }

    @Override
    public String send(UUID userId, List<OutgoingNotification> notifications) {
      sent.addAll(notifications);
      return "push-" + sent.size();
    }
  }
}
