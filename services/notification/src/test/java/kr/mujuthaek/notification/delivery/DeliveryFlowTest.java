package kr.mujuthaek.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujuthaek.notification.TestcontainersConfiguration;
import kr.mujuthaek.notification.delivery.email.EmailMessage;
import kr.mujuthaek.notification.delivery.email.EmailSender;
import kr.mujuthaek.notification.notification.NotificationRequest;
import kr.mujuthaek.notification.notification.NotificationRequest.Recipient;
import kr.mujuthaek.notification.notification.NotificationService;
import kr.mujuthaek.notification.notification.NotificationType;
import kr.mujuthaek.notification.notification.TargetType;
import kr.mujuthaek.notification.notification.UserDataCleaner;
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
public class DeliveryFlowTest {

  static final UUID USER = UUID.fromString("0192b6f0-0000-7000-8000-0000000000a1");
  static final Recipient EMAIL_ONLY = new Recipient(USER, "me@example.com", false);

  @Autowired NotificationService service;
  @Autowired DeliveryWorker worker;
  @Autowired UserDataCleaner cleaner;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired MutableClock clock;
  @Autowired RecordingEmailSender email;
  @Autowired FakePushChannel push;

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from deliveries").update();
    email.sent.clear();
    email.failure.set(null);
    push.sent.clear();
    push.devices.clear();
    clock.set("2026-10-23T10:00");
  }

  @Test
  void immediateNotificationIsSentOnceAndAddressIsCleared() {
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", EMAIL_ONLY);
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", EMAIL_ONLY); // 같은 알림 다시

    worker.runOnce();

    assertThat(email.sent).hasSize(1);
    assertThat(email.sent.getFirst().to()).isEqualTo("me@example.com");
    assertThat(email.sent.getFirst().subject()).isEqualTo("[무주택자들] 제목 NOTICE:X:CORRECTED:2");
    assertThat(status(Channel.EMAIL)).containsExactly("SENT");
    assertThat(jdbc.sql("select address from deliveries").query(String.class).optional())
        .isEmpty(); // 보낸 뒤에는 주소를 남기지 않아요
  }

  @Test
  void recipientWithoutAddressGetsNoDelivery() {
    request(NotificationType.CORRECTED, "NOTICE:X:CORRECTED:2", new Recipient(USER, null, false));

    assertThat(jdbc.sql("select count(*) from deliveries").query(Long.class).single()).isZero();
  }

  @Test
  void newNoticesAreBundledIntoOneEmailAtSixPm() {
    request(NotificationType.NEW_NOTICE, "NOTICE:A:NEW", EMAIL_ONLY);
    request(NotificationType.NEW_NOTICE, "NOTICE:B:NEW", EMAIL_ONLY);

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
    clock.set("2026-10-23T23:00");
    request(
        NotificationType.DEADLINE_D1,
        "NOTICE:X:D1:2026-10-24",
        new Recipient(USER, "me@example.com", true));

    worker.runOnce();
    assertThat(email.sent).isEmpty();

    clock.set("2026-10-24T08:00");
    worker.runOnce();
    assertThat(email.sent).hasSize(1);
  }

  @Test
  void temporaryFailureIsRetriedAndPermanentFailureStops() {
    email.failure.set(new DeliveryException("SES_TooManyRequests", false));
    request(NotificationType.DEADLINE_D3, "NOTICE:X:D3:2026-10-26", EMAIL_ONLY);

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
  void withdrawnUserLosesPendingDeliveries() {
    request(NotificationType.NEW_NOTICE, "NOTICE:A:NEW", EMAIL_ONLY);

    tx.executeWithoutResult(s -> cleaner.delete(USER));

    clock.set("2026-10-23T18:00");
    worker.runOnce();
    assertThat(email.sent).isEmpty();
  }

  @Test
  void addedChannelGetsItsOwnDeliveryWithoutDigest() {
    push.devices.addAll(List.of("device-1", "device-2"));
    request(NotificationType.NEW_NOTICE, "NOTICE:A:NEW", EMAIL_ONLY);

    worker.runOnce();

    assertThat(push.sent).containsExactly("device-1", "device-2"); // 푸시는 묶지 않아서 기기마다 바로
    assertThat(email.sent).isEmpty(); // 이메일은 18시 묶음
    assertThat(status(Channel.PUSH)).containsExactly("SENT"); // 회원 1명·채널 1개 = 발송 줄 1개
  }

  private void request(NotificationType type, String dedupKey, Recipient recipient) {
    UUID targetId = UUID.randomUUID();
    NotificationRequest request =
        new NotificationRequest(
            type,
            dedupKey,
            new NotificationRequest.Target(TargetType.NOTICE, targetId),
            "제목 " + dedupKey,
            "본문",
            "/notices/" + targetId,
            List.of(recipient));
    tx.executeWithoutResult(s -> service.handle(request));
  }

  private List<String> status(Channel channel) {
    return jdbc.sql("select status from deliveries where channel = :c order by status")
        .param("c", channel.name())
        .query(String.class)
        .list();
  }

  /** 다른 테스트도 같은 설정을 써서 테스트 컨텍스트(컨테이너)를 하나만 띄워요 */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Fakes {

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

  public static class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public void set(String seoulLocalDateTime) {
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

  public static class RecordingEmailSender implements EmailSender {
    public final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
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

  /**
   * 웹 푸시 자리. 실제 PushChannel은 요청의 recipients[]에 api가 넣어 준 구독 기기 목록을 꺼내고, 기기마다 web-push로 보내요. 이 가짜는
   * 모든 회원이 {@link #devices}에 구독했다고 쳐요
   */
  static class FakePushChannel implements NotificationChannel {
    final List<String> devices = new ArrayList<>();
    final List<String> sent = new CopyOnWriteArrayList<>();

    @Override
    public Channel channel() {
      return Channel.PUSH;
    }

    @Override
    public Optional<String> address(Recipient recipient) {
      return devices.isEmpty() ? Optional.empty() : Optional.of(String.join(",", devices));
    }

    @Override
    public boolean supportsDigest() {
      return false;
    }

    @Override
    public String send(String address, List<OutgoingNotification> notifications) {
      sent.addAll(List.of(address.split(","))); // 기기마다 보내요
      return "push-" + sent.size();
    }
  }
}
