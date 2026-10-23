package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.eligibility.dto.request.ApplicantRequest;
import kr.mujuthaek.api.eligibility.model.MatchResult;
import kr.mujuthaek.api.eligibility.model.MissingInput;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Eligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 목록 판정 서비스를 실제 스프링·DB(기준값·예치기준금액 마이그레이션)로 불러요 (주거 중심, 사업공간·창업지원은 같은 모양인지). notice 대신 가짜 후보(정답 케이스
 * 파일의 틀)를 넣고, 지원 가능·확인 필요만 남는지, regionCodes가 그대로 실리는지, 정렬·개수, 틀이 없거나 틀린 공고, 로그에 입력이 남지 않는지 봐요. 순위
 * 정렬은 {@link MatchOrderTest}에서 봐요.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MatchQueryServiceTest.FakeNotices.class})
@ExtendWith(OutputCaptureExtension.class)
class MatchQueryServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final UUID JEONSE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  static final UUID SEORIPUL = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  static final UUID NO_FRAME = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  static final UUID BAD_FRAME = UUID.fromString("00000000-0000-0000-0000-00000000000d");
  static final UUID SEONGDONG = UUID.fromString("00000000-0000-0000-0000-00000000000e");
  static final UUID GVC = UUID.fromString("00000000-0000-0000-0000-00000000000f");

  /**
   * notice 대신 쓰는 후보. 주거 4개: 장기전세(서초), 서리풀 아트타운(서초), 틀 없는 공고(전국), 다른 분야 틀이 들어간 공고(부산). 사업공간 1개(성동
   * 안심상가), 창업지원 1개(K-Startup).
   */
  @TestConfiguration
  static class FakeNotices {
    @Bean
    @Primary
    JudgmentCandidates fakeNotices() throws IOException {
      List<NoticeCandidate> housing =
          List.of(
              new NoticeCandidate(
                  JEONSE,
                  1,
                  "장기전세주택 85㎡ 초과",
                  LocalDate.parse("2026-10-20"),
                  LocalDate.parse("2026-11-10"),
                  List.of("1165010100"),
                  frame("housing/long-term-jeonse-85plus")),
              new NoticeCandidate(
                  SEORIPUL,
                  2,
                  "서리풀 아트타운",
                  LocalDate.parse("2026-10-20"),
                  LocalDate.parse("2026-10-30"),
                  List.of("1165010800", "1165010700"),
                  frame("housing/seoripul-artown")),
              new NoticeCandidate(
                  NO_FRAME,
                  1,
                  "자격 조건 정리 중인 공고",
                  null,
                  LocalDate.parse("2026-10-25"),
                  List.of(),
                  null),
              new NoticeCandidate(
                  BAD_FRAME,
                  1,
                  "틀이 잘못 들어간 공고",
                  null,
                  null,
                  List.of("2600000000"),
                  JSON.readTree("{\"schema\": \"business-space-judgment.v1\"}")));
      List<NoticeCandidate> businessSpace =
          List.of(
              new NoticeCandidate(
                  SEONGDONG,
                  1,
                  "성동 안심상가",
                  null,
                  LocalDate.parse("2026-11-01"),
                  List.of("1120011500"),
                  frame("business-space/seongdong-safe-shop")));
      List<NoticeCandidate> startupSupport =
          List.of(
              new NoticeCandidate(
                  GVC,
                  1,
                  "K-Startup 글로벌 진출",
                  null,
                  LocalDate.parse("2026-11-05"),
                  List.of(),
                  frame("startup-support/kstartup-gvc")));
      return field ->
          switch (field) {
            case HOUSING -> housing;
            case BUSINESS_SPACE -> businessSpace;
            case STARTUP_SUPPORT -> startupSupport;
          };
    }
  }

  @Autowired private MatchQueryService matches;

  private static JsonNode frame(String file) throws IOException {
    try (InputStream in =
        MatchQueryServiceTest.class.getResourceAsStream("/eligibility/frames/" + file + ".json")) {
      return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get("frame");
    }
  }

  private static ApplicantRequest applicant(String json) {
    return JSON.readValue(json, ApplicantRequest.class);
  }

  private static List<UUID> ids(MatchResult result) {
    return result.items().stream().map(MatchResult.Item::noticeId).toList();
  }

  @Test
  void businessSpaceListUsesTheSameShape() {
    // 성동 안심상가 정답 케이스 0번(법인·사회적기업·성동 사업장) → 지원 가능, 1번(개인) → 불가라 빠져요
    MatchResult eligible =
        matches.matchBusinessSpace(
            applicant(
                """
                {"business": {"applicantType": "CORPORATION", "hasSite": true, "siteRegionCode": "1120011500"},
                 "statuses": ["SOCIAL_ENTERPRISE"]}"""));
    assertThat(eligible.field()).isEqualTo("BUSINESS_SPACE");
    assertThat(eligible.items().getFirst().noticeId()).isEqualTo(SEONGDONG);
    assertThat(eligible.items().getFirst().status()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(eligible.items().getFirst().regionCodes()).containsExactly("1120011500");
    assertThat(eligible.counts().eligible()).isEqualTo(1);

    MatchResult dropped =
        matches.matchBusinessSpace(
            applicant(
                """
                {"business": {"applicantType": "INDIVIDUAL"}, "statuses": ["SOCIAL_ENTERPRISE"]}"""));
    assertThat(dropped.items()).isEmpty();
    assertThat(dropped.counts().eligible()).isZero();
  }

  @Test
  void startupSupportListUsesTheSameShape() {
    // K-Startup 정답 케이스: 2023-10-17 개업 → 지원 가능, 2023-10-16 개업 → 불가라 빠져요
    MatchResult eligible =
        matches.matchStartupSupport(
            applicant("{\"business\": {\"history\": [{\"openedOn\": \"2023-10-17\"}]}}"));
    assertThat(eligible.field()).isEqualTo("STARTUP_SUPPORT");
    assertThat(eligible.items().getFirst().noticeId()).isEqualTo(GVC);
    assertThat(eligible.items().getFirst().status()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(eligible.items().getFirst().regionCodes()).isEmpty();

    MatchResult dropped =
        matches.matchStartupSupport(
            applicant("{\"business\": {\"history\": [{\"openedOn\": \"2023-10-16\"}]}}"));
    assertThat(dropped.items()).isEmpty();
  }

  // 장기전세 정답 케이스 0번: 서울 거주, 가입 30개월, 예치금 1,000만원 → 지원 가능
  private static final String SEOUL_10M =
      """
      {"residenceRegionCode": "1165000000", "household": {"homeless": true},
       "subscription": {"months": 30, "balanceWon": 10000000}}""";

  @Test
  void keepsEligibleAndNeedsCheckNoticesInOrderWithRegionCodes() {
    MatchResult result = matches.matchHousing(applicant(SEOUL_10M));

    assertThat(result.field()).isEqualTo("HOUSING");
    // 지원 가능 → 확인 필요 순, 확인 필요끼리는 마감 빠른 순 (마감 없는 공고는 맨 뒤)
    assertThat(ids(result)).containsExactly(JEONSE, NO_FRAME, SEORIPUL, BAD_FRAME);
    assertThat(result.counts()).isEqualTo(new MatchResult.Counts(1, 3, 2));

    // 지원 가능: 표준 공고 정보와 regionCodes를 그대로 옮기고, 충족한 대상군을 알려줘요
    MatchResult.Item jeonse = result.items().get(0);
    assertThat(jeonse.status()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(jeonse.title()).isEqualTo("장기전세주택 85㎡ 초과");
    assertThat(jeonse.version()).isEqualTo(1);
    assertThat(jeonse.regionCodes()).containsExactly("1165010100");
    assertThat(jeonse.applyStart()).isEqualTo(LocalDate.parse("2026-10-20"));
    assertThat(jeonse.applyEnd()).isEqualTo(LocalDate.parse("2026-11-10"));
    assertThat(jeonse.reason()).isNull();
    // rank 칸이 없는 틀(순위 정렬은 MatchOrderTest)이라 내 순위도 없어요
    assertThat(jeonse.rank()).isNull();
    assertThat(jeonse.groups()).isNotEmpty();
    assertThat(jeonse.missingInputs()).isEmpty();
    assertThat(jeonse.openQuestions()).isZero();

    // 틀이 없는 공고: 확인 필요 + 자격 조건 정리 중, 전국 공고라 regionCodes가 비어 있어요
    MatchResult.Item noFrame = result.items().get(1);
    assertThat(noFrame.status()).isEqualTo(Eligibility.CHECK_NEEDED);
    assertThat(noFrame.reason()).isEqualTo("CONDITIONS_NOT_READY");
    assertThat(noFrame.regionCodes()).isEmpty();
    assertThat(noFrame.groups()).isEmpty();

    // 서리풀: 나이·소득을 안 넣었고 예술인 질문에 답이 없어서 확인 필요. 지역 코드는 단지 두 곳 그대로
    MatchResult.Item seoripul = result.items().get(2);
    assertThat(seoripul.status()).isEqualTo(Eligibility.CHECK_NEEDED);
    assertThat(seoripul.reason()).isNull();
    assertThat(seoripul.version()).isEqualTo(2);
    assertThat(seoripul.regionCodes()).containsExactly("1165010800", "1165010700");
    assertThat(seoripul.groups()).isNotEmpty();
    assertThat(seoripul.missingInputs()).extracting(MissingInput::field).contains("birthDate");
    assertThat(seoripul.openQuestions()).isEqualTo(1);

    // 다른 분야 틀이 들어간 공고는 오류로 끊지 않고 확인 필요로 둬요
    MatchResult.Item badFrame = result.items().get(3);
    assertThat(badFrame.status()).isEqualTo(Eligibility.CHECK_NEEDED);
    assertThat(badFrame.reason()).isEqualTo("CONDITIONS_NOT_READY");
    assertThat(badFrame.regionCodes()).containsExactly("2600000000");
  }

  @Test
  void dropsNotEligibleNotices() {
    // 장기전세 정답 케이스 1번: 예치금 900만원 → 지원 불가라 목록에서 빠져요
    MatchResult result = matches.matchHousing(applicant(SEOUL_10M.replace("10000000", "9000000")));

    assertThat(ids(result)).doesNotContain(JEONSE).hasSize(3);
    assertThat(result.items())
        .extracting(MatchResult.Item::status)
        .containsOnly(Eligibility.CHECK_NEEDED);
    assertThat(result.counts().eligible()).isZero();
    assertThat(result.counts().checkNeeded()).isEqualTo(3);
  }

  @Test
  void emptyInputLeavesEveryNoticeToCheck() {
    // 아무것도 넣지 않으면 불충족이 될 조건이 없어서 모든 공고가 확인 필요예요
    MatchResult result = matches.matchHousing(null);

    assertThat(result.items()).hasSize(4);
    assertThat(result.items())
        .extracting(MatchResult.Item::status)
        .containsOnly(Eligibility.CHECK_NEEDED);
    assertThat(result.items().getFirst().noticeId()).isEqualTo(NO_FRAME);
    assertThat(result.items())
        .filteredOn(i -> i.noticeId().equals(JEONSE))
        .flatExtracting(MatchResult.Item::missingInputs)
        .extracting(MissingInput::field)
        .contains("subscription.months");
  }

  @Test
  void rejectsBrokenInputLikeTheDetailJudgment() {
    assertThatThrownBy(
            () -> matches.matchHousing(applicant("{\"household\": {\"monthlyIncomeWon\": -1}}")))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
  }

  @Test
  void personalInputsNeverReachTheLogs(CapturedOutput output) {
    ApplicantRequest applicant =
        applicant(
            """
            {"birthDate": "1987-06-05", "residenceRegionCode": "1165000000",
             "household": {"homeless": true, "monthlyIncomeWon": 7654321, "totalAssetWon": 123456789},
             "subscription": {"months": 30, "balanceWon": 10000000}}""");
    MatchResult result = matches.matchHousing(applicant);

    assertThat(JSON.writeValueAsString(result))
        .doesNotContain("7654321")
        .doesNotContain("123456789");
    // 틀 문제는 공고 ID로 로그에 남기고, 사용자 입력은 남기지 않아요
    assertThat(output.getAll())
        .contains(BAD_FRAME.toString())
        .doesNotContain("7654321")
        .doesNotContain("123456789")
        .doesNotContain("1987-06-05");
  }
}
