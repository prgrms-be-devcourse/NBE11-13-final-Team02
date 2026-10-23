package kr.mujuthaek.notification.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 알림 문구(제목·본문). 채널과 상관없는 내용만 만들어 notifications에 남겨요. 메일 모양(인사말·링크·수신 설정 안내)이나 푸시 모양은 채널이 입혀요. */
@Component
public class NotificationTemplates {

  /** 정정 알림에 쓰는 바뀐 항목 이름 (NoticeUpdated.changedFields). 모르는 값은 그대로 보여 주지 않고 빼요 */
  private static final Map<String, String> FIELD_LABELS =
      Map.of(
          "notice", "기본 정보",
          "schedules", "일정",
          "supplyGroups", "공급·가격",
          "eligibility", "자격",
          "selectionRules", "선정 방법",
          "requiredDocuments", "제출 서류",
          "locations", "위치");

  public record Content(String title, String body) {}

  public Content render(NotificationRequest request) {
    NotificationRequest.Target target = request.target();
    String kind = target.type().label();
    String name = target.title();
    return switch (request.type()) {
      case NEW_NOTICE, NEW_SUPPORT ->
          new Content(
              "새 " + kind + ": " + name, "관심 조건에 맞는 새 " + kind + " 소식이에요." + summary(target));
      case DEADLINE_D3 ->
          new Content(
              "마감 3일 전: " + name, "관심 " + kind + " 접수가 " + date(target.applyEnd()) + "에 마감돼요.");
      case DEADLINE_D1 ->
          new Content(
              "내일 마감: " + name, "관심 " + kind + " 접수가 내일(" + date(target.applyEnd()) + ") 마감돼요.");
      case CORRECTED ->
          new Content(
              "정정: " + name,
              "관심 " + kind + "의 " + fields(request.correction().changedFields()) + " 내용이 바뀌었어요.");
      case FOLLOW_UP -> {
        NotificationRequest.FollowUp followUp = request.followUp();
        String postedOn = followUp.postedOn() == null ? "" : " (" + date(followUp.postedOn()) + ")";
        yield new Content(
            "후속 안내: " + name,
            "관심 " + kind + "에 '" + followUp.title() + "'" + postedOn + " 안내가 올라왔어요.");
      }
    };
  }

  private static String summary(NotificationRequest.Target target) {
    StringBuilder sb = new StringBuilder();
    if (target.provider() != null && !target.provider().isBlank()) {
      sb.append(" ").append(target.provider());
    }
    if (target.applyEnd() != null) {
      sb.append(sb.isEmpty() ? " " : " · ").append(date(target.applyEnd())).append(" 마감");
    }
    return sb.toString();
  }

  private static String fields(List<String> changedFields) {
    String labels =
        changedFields == null
            ? ""
            : changedFields.stream()
                .map(FIELD_LABELS::get)
                .filter(label -> label != null)
                .distinct()
                .collect(Collectors.joining(", "));
    return labels.isEmpty() ? "일부" : labels;
  }

  private static String date(LocalDate date) {
    return date == null ? "곧" : date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
  }
}
