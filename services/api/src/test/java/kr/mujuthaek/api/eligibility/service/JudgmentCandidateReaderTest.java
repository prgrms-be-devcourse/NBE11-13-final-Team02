package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.benefit.service.ProgramForJudgment;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Field;
import kr.mujuthaek.api.notice.service.NoticeField;
import kr.mujuthaek.api.notice.service.NoticeForJudgment;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 분야마다 맞는 모듈에서 후보를 가져오고(주거·사업공간 → notice, 창업지원 → benefit), 값을 그대로 옮기는지 봐요. */
class JudgmentCandidateReaderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID NOTICE = UUID.randomUUID();
  private static final UUID PROGRAM = UUID.randomUUID();

  private static final JsonNode SHOP_FRAME =
      JSON.readTree("{\"schema\": \"business-space-judgment.v1\"}");
  private static final JsonNode STARTUP_FRAME =
      JSON.readTree("{\"schema\": \"startup-support-judgment.v1\"}");

  private final NoticeQueryService notices =
      new NoticeQueryService() {
        @Override
        public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
          return field == NoticeField.BUSINESS_SPACE
              ? List.of(
                  new NoticeForJudgment(
                      NOTICE,
                      3,
                      "행복상가",
                      LocalDate.parse("2026-10-20"),
                      LocalDate.parse("2026-10-30"),
                      List.of("1150010100"),
                      SHOP_FRAME))
              : List.of();
        }
      };

  private final BenefitQueryService benefits =
      new BenefitQueryService() {
        @Override
        public List<ProgramForJudgment> findOpenProgramsForJudgment() {
          return List.of(
              new ProgramForJudgment(
                  PROGRAM,
                  1,
                  "예비창업패키지",
                  null,
                  LocalDate.parse("2026-11-05"),
                  List.of(),
                  STARTUP_FRAME));
        }
      };

  private final JudgmentCandidateReader reader = new JudgmentCandidateReader(notices, benefits);

  @Test
  void housingAndBusinessSpaceComeFromNotice() {
    assertThat(reader.openNotices(Field.BUSINESS_SPACE))
        .containsExactly(
            new NoticeCandidate(
                NOTICE,
                3,
                "행복상가",
                LocalDate.parse("2026-10-20"),
                LocalDate.parse("2026-10-30"),
                List.of("1150010100"),
                SHOP_FRAME));
    assertThat(reader.openNotices(Field.HOUSING)).isEmpty();
  }

  @Test
  void startupSupportComesFromBenefit() {
    assertThat(reader.openNotices(Field.STARTUP_SUPPORT))
        .containsExactly(
            new NoticeCandidate(
                PROGRAM,
                1,
                "예비창업패키지",
                null,
                LocalDate.parse("2026-11-05"),
                List.of(),
                STARTUP_FRAME));
  }

  @Test
  void stubsGiveNothingUntilDataIsStored() {
    assertThat(new NoticeQueryService().findOpenForJudgment(NoticeField.HOUSING)).isEmpty();
    assertThat(new BenefitQueryService().findOpenProgramsForJudgment()).isEmpty();
  }
}
