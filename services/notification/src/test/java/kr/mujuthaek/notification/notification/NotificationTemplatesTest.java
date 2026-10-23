package kr.mujuthaek.notification.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

  private final NotificationTemplates templates = new NotificationTemplates();

  @Test
  void newNotice() {
    var content =
        templates.render(request(NotificationType.NEW_NOTICE, TargetType.NOTICE, null, null));
    assertThat(content.title()).isEqualTo("새 공고: 강동구 행복주택");
    assertThat(content.body()).isEqualTo("관심 조건에 맞는 새 공고 소식이에요. SH · 11월 6일 마감");
  }

  @Test
  void correctedShowsOnlyKnownFields() {
    var content =
        templates.render(
            request(
                NotificationType.CORRECTED,
                TargetType.BENEFIT,
                new NotificationRequest.Correction(List.of("schedules", "eligibility", "sources")),
                null));
    assertThat(content.body()).isEqualTo("관심 지원 사업의 일정, 자격 내용이 바뀌었어요.");
  }

  @Test
  void followUp() {
    var content =
        templates.render(
            request(
                NotificationType.FOLLOW_UP,
                TargetType.SPACE_RECRUITMENT,
                null,
                new NotificationRequest.FollowUp("서류심사 결과 발표", LocalDate.of(2026, 12, 1))));
    assertThat(content.body()).isEqualTo("관심 사업공간 모집에 '서류심사 결과 발표' (12월 1일) 안내가 올라왔어요.");
  }

  private static NotificationRequest request(
      NotificationType type,
      TargetType targetType,
      NotificationRequest.Correction correction,
      NotificationRequest.FollowUp followUp) {
    UUID id = UUID.randomUUID();
    return new NotificationRequest(
        type,
        "X",
        List.of(UUID.randomUUID()),
        new NotificationRequest.Target(
            targetType, id, "강동구 행복주택", "SH", LocalDate.of(2026, 11, 6), "/notices/" + id),
        correction,
        followUp);
  }
}
