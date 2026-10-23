package kr.mujuthaek.api.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.mapper.MatchMapper;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Field;
import kr.mujuthaek.api.notice.service.NoticeField;
import kr.mujuthaek.api.notice.service.NoticeForJudgment;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세 분야 모두 notice에서 같은 분야로 후보를 가져오고(주거 → HOUSING, 창업지원 → STARTUP_SUPPORT, 사업공간 → BUSINESS_SPACE), 값을
 * 그대로 옮기는지 봐요.
 */
class JudgmentCandidateReaderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID SHOP = UUID.randomUUID();
  private static final UUID PROGRAM = UUID.randomUUID();
  private static final UUID UNKNOWN_REGION = UUID.randomUUID();

  private static final JsonNode SHOP_FRAME =
      JSON.readTree("{\"schema\": \"business-space-judgment.v1\"}");
  private static final JsonNode STARTUP_FRAME =
      JSON.readTree("{\"schema\": \"startup-support-judgment.v1\"}");

  private final NoticeQueryService notices =
      new NoticeQueryService() {
        @Override
        public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
          return switch (field) {
            case BUSINESS_SPACE ->
                List.of(
                    new NoticeForJudgment(
                        SHOP,
                        3,
                        "행복상가",
                        LocalDate.parse("2026-10-20"),
                        LocalDate.parse("2026-10-30"),
                        List.of("1150010100"),
                        SHOP_FRAME));
            case STARTUP_SUPPORT ->
                List.of(
                    new NoticeForJudgment(
                        PROGRAM,
                        1,
                        "예비창업패키지",
                        null,
                        LocalDate.parse("2026-11-05"),
                        List.of(),
                        STARTUP_FRAME));
            case HOUSING ->
                List.of(
                    new NoticeForJudgment(UNKNOWN_REGION, 1, "지역을 모르는 공고", null, null, null, null));
          };
        }
      };

  private final JudgmentCandidateReader reader = new JudgmentCandidateReader(notices);

  @Test
  void businessSpaceComesFromNotice() {
    assertThat(reader.openNotices(Field.BUSINESS_SPACE))
        .containsExactly(
            new NoticeCandidate(
                SHOP,
                3,
                "행복상가",
                LocalDate.parse("2026-10-20"),
                LocalDate.parse("2026-10-30"),
                List.of("1150010100"),
                SHOP_FRAME));
  }

  @Test
  void unknownRegionStaysNullNotNationwide() {
    NoticeCandidate candidate = reader.openNotices(Field.HOUSING).getFirst();

    assertThat(candidate.noticeId()).isEqualTo(UNKNOWN_REGION);
    // 빈 목록(전국)으로 바꾸지 않아요: 바꾸면 모든 지역에 맞는 공고처럼 보여요
    assertThat(candidate.regionCodes()).isNull();
    assertThat(MatchMapper.notReady(candidate).regionCodes()).isNull();
  }

  @Test
  void startupSupportAlsoComesFromNotice() {
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
  void stubGivesNothingUntilDataIsStored() {
    for (NoticeField field : NoticeField.values()) {
      assertThat(new NoticeQueryService().findOpenForJudgment(field)).as(field.name()).isEmpty();
    }
  }
}
