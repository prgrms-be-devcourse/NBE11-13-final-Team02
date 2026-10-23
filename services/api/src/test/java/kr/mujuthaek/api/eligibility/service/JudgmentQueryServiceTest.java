package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import kr.mujuthaek.api.TestcontainersConfiguration;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.eligibility.dto.request.ApplicantRequest;
import kr.mujuthaek.api.eligibility.model.JudgmentResult;
import kr.mujuthaek.api.eligibility.model.MissingInput;
import kr.mujuthaek.api.eligibility.rule.Eligibility;
import kr.mujuthaek.api.eligibility.rule.Verdict;
import kr.mujuthaek.api.eligibility.rule.frame.Answer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 틀 하나 판정 서비스를 실제 스프링·DB(Testcontainers, 기준값 마이그레이션)로 불러요. 틀 + 입력 → 정제된 결과, 오류 코드까지 봐요. 결과에 사용자가 넣은
 * 값이 없는지도 봐요.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JudgmentQueryServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private JudgmentQueryService judgments;

  /** 정답 케이스 파일의 틀 (정의서 예시 그대로). */
  private static JsonNode frame(String file) throws IOException {
    try (InputStream in =
        JudgmentQueryServiceTest.class.getResourceAsStream(
            "/eligibility/frames/" + file + ".json")) {
      return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get("frame");
    }
  }

  private static ApplicantRequest applicant(String json) {
    return JSON.readValue(json, ApplicantRequest.class);
  }

  private static void rejected(Runnable call, ErrorCode code, String detail) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(code);
              assertThat(e.getMessage()).contains(detail);
            });
  }

  @Test
  void housingJudgesTheSeoripulNotice() throws Exception {
    ApplicantRequest applicant =
        applicant(
            """
            {"birthDate": "1999-05-05", "residenceRegionCode": "1165000000",
             "household": {"size": 1, "homeless": true, "monthlyIncomeWon": 3000000}}""");

    JudgmentResult result =
        judgments.judgeHousing(
            frame("housing/seoripul-artown"), applicant, Map.of("arts-proof", Answer.YES));

    assertThat(result.field()).isEqualTo("HOUSING");
    assertThat(result.result()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(result.referenceDate()).isEqualTo(LocalDate.parse("2026-10-16"));
    JudgmentResult.Group artist = result.groups().getFirst();
    assertThat(artist.id()).isEqualTo("artist");
    assertThat(artist.result()).isEqualTo(Verdict.MET);
    JudgmentResult.Criterion incomeOrAssets = artist.criteria().get(4);
    assertThat(incomeOrAssets.kind()).isEqualTo("ANY_OF");
    assertThat(incomeOrAssets.options().get(1).result()).isEqualTo(Verdict.MET);
    assertThat(incomeOrAssets.options().get(1).criteria().getFirst().summary())
        .isEqualTo("세대 월소득 3,432,027원 이하 (도시근로자 가구원수별 월평균소득 2025년 1인 × 90%)");
    assertThat(result.questions().getFirst().id()).isEqualTo("arts-proof");
    assertThat(result.questions().getFirst().answer()).isEqualTo(Answer.YES);
    assertThat(result.notes().getFirst().kind()).isEqualTo("RULE");
    assertThat(result.missingInputs()).isEmpty();
    // 결과에는 사용자가 넣은 값(소득·생년월일)이 없어요
    assertThat(JSON.writeValueAsString(result))
        .doesNotContain("3,000,000")
        .doesNotContain("1999-05-05");
  }

  @Test
  void anEmptyApplicantGetsTheQuestionnaire() throws Exception {
    JudgmentResult result = judgments.judgeHousing(frame("housing/seoripul-artown"), null, null);

    assertThat(result.result()).isEqualTo(Eligibility.NEEDS_CHECK);
    assertThat(result.missingInputs())
        .extracting(MissingInput::field)
        .contains("household.monthlyIncomeWon", "birthDate");
    assertThat(result.questions().getFirst().answer()).isNull();
    assertThat(result.questions().getFirst().purpose()).isEqualTo("ELIGIBILITY");
  }

  @Test
  void businessSpaceJudgesTheYangsanNotice() throws Exception {
    ApplicantRequest applicant =
        applicant(
            """
            {"birthDate": "1996-02-01", "korean": true,
             "business": {"applicantType": "INDIVIDUAL", "history": [], "industryCode": "56111"},
             "statuses": [], "restrictions": []}""");
    Map<String, Answer> answers =
        Map.of(
            "real-use", Answer.YES,
            "not-general-tenant", Answer.NO,
            "industry-other", Answer.NO,
            "youth-idea", Answer.YES);

    JudgmentResult result =
        judgments.judgeBusinessSpace(frame("business-space/yangsan-hope-shop"), applicant, answers);

    assertThat(result.field()).isEqualTo("BUSINESS_SPACE");
    assertThat(result.result()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(result.groups().getFirst().id()).isEqualTo("youth");
    assertThat(result.groups().getFirst().result()).isEqualTo(Verdict.MET);
  }

  @Test
  void startupSupportShowsPreferencesSeparately() throws Exception {
    ApplicantRequest applicant =
        applicant(
            """
            {"birthDate": "1993-04-10", "business": {"history": [{"openedOn": "2024-05-02"}], "industryCode": "58222"},
             "statuses": ["SME"], "restrictions": []}""");
    Map<String, Answer> answers =
        Map.of(
            "startup-recognized", Answer.YES,
            "industry-other", Answer.NO,
            "program-history", Answer.NO,
            "host-staff", Answer.NO,
            "ces-award-ask", Answer.YES);

    JudgmentResult result =
        judgments.judgeStartupSupport(
            frame("startup-support/early-startup-package"), applicant, answers);

    assertThat(result.result()).isEqualTo(Eligibility.ELIGIBLE);
    JudgmentResult.Preference award = result.preferences().getFirst();
    assertThat(award.id()).isEqualTo("ces-award");
    assertThat(award.result()).isEqualTo(Verdict.MET);
    assertThat(award.points()).isEqualByComparingTo("2");
    assertThat(result.questions())
        .filteredOn(q -> q.purpose().equals("PREFERENCE"))
        .extracting(JudgmentResult.Question::id)
        .contains("climate-tech-ask");
  }

  @Test
  void aFrameOfAnotherFieldIsRejected() throws Exception {
    JsonNode housing = frame("housing/seoripul-artown");
    rejected(
        () -> judgments.judgeBusinessSpace(housing, null, null),
        ErrorCode.ELIGIBILITY_FIELD_MISMATCH,
        "housing-judgment.v1");
  }

  @Test
  void aBrokenFrameSaysWhereItIsBroken() {
    JsonNode frame =
        JSON.readTree(
            """
            {"schema": "housing-judgment.v1", "rawPostId": "p", "rawVersion": 1, "referenceDate": "2026-10-16",
             "groups": [{"id": "g", "name": "전체", "criteria": [{"id": "x", "kind": "REVENUE"}]}]}""");
    rejected(
        () -> judgments.judgeHousing(frame, null, null),
        ErrorCode.ELIGIBILITY_INVALID_FRAME,
        "frame.groups[0].criteria[0].kind");
  }

  @Test
  void aWrongApplicantValueIsRejectedWithoutEchoingIt() throws Exception {
    JsonNode frame = frame("housing/seoripul-artown");
    ApplicantRequest applicant = applicant("{\"household\": {\"monthlyIncomeWon\": -7654321}}");
    rejected(
        () -> judgments.judgeHousing(frame, applicant, null),
        ErrorCode.INVALID_REQUEST,
        "profile.household.monthlyIncomeWon");
    assertThatThrownBy(() -> judgments.judgeHousing(frame, applicant, null))
        .hasMessageNotContaining("7654321");
  }

  @Test
  void theFrameIsRequired() {
    rejected(() -> judgments.judgeHousing(null, null, null), ErrorCode.INVALID_REQUEST, "frame");
  }
}
