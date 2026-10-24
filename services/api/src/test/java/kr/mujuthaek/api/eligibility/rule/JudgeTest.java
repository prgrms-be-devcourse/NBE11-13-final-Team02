package kr.mujuthaek.api.eligibility.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kr.mujuthaek.api.eligibility.mapper.JudgmentReader;
import kr.mujuthaek.api.eligibility.mapper.JudgmentReaderTest;
import kr.mujuthaek.api.eligibility.rule.Judgment.Basics;
import org.junit.jupiter.api.Test;

class JudgeTest {

  private static final Basics B = new Basics("x", null, null, false, null);
  private static final LocalDate REF = LocalDate.of(2026, 10, 16);

  /** 청년(만 19~39세)과 신혼부부(혼인 7년 이내) 두 대상, 공통 조건은 무주택. */
  private static Judgment twoGroups() {
    return new Judgment(
        REF,
        List.of(
            new Judgment.Homeless(
                new Basics("homeless", null, null, false, null), "HOUSEHOLD", null)),
        List.of(
            new Judgment.Group(
                "youth",
                "청년",
                List.of(new Judgment.Age(new Basics("age", null, null, false, null), 19, 39))),
            new Judgment.Group(
                "newlywed",
                "신혼부부",
                List.of(
                    new Judgment.Marriage(
                        new Basics("marriage", null, null, false, null), "MARRIED", 7, true)))));
  }

  @Test
  void oneEligibleTargetMakesTheNoticeEligible() {
    var a =
        new ApplicantBuilder().birth(1997, 3, 15).homelessSince(2022, 1).marital("SINGLE").build();

    var result = Judge.evaluate(twoGroups(), a);

    assertThat(result.overall()).isEqualTo(Overall.ELIGIBLE);
    assertThat(result.targets())
        .extracting(Judge.Target::overall)
        .containsExactly(Overall.ELIGIBLE, Overall.INELIGIBLE);
    assertThat(result.targets().get(0).items())
        .extracting(Item::key)
        .containsExactly("HOMELESS", "AGE");
    assertThat(result.targets().get(0).summary()).isEqualTo(new Judge.Summary(2, 2, 0, 0));
  }

  @Test
  void missingInputsMakeItNeedsInputAndListWhatToFill() {
    var a = new ApplicantBuilder().birth(1997, 3, 15).build();

    var result = Judge.evaluate(twoGroups(), a);

    assertThat(result.overall()).isEqualTo(Overall.NEEDS_INPUT);
    assertThat(result.targets().get(0).overall()).isEqualTo(Overall.NEEDS_INPUT);
    assertThat(result.targets().get(0).summary()).isEqualTo(new Judge.Summary(2, 1, 0, 1));
    assertThat(result.missingInputs())
        .containsExactly("applicant.homelessSince", "applicant.marital");
  }

  @Test
  void ineligibleWhenEveryTargetFails() {
    var a = new ApplicantBuilder().birth(1970, 1, 1).homeOwner(true).marital("SINGLE").build();

    var result = Judge.evaluate(twoGroups(), a);

    assertThat(result.overall()).isEqualTo(Overall.INELIGIBLE);
    assertThat(result.missingInputs()).isEmpty();
  }

  @Test
  void serifulYouthArtistNeedsTheArtistQuestionAnswered() throws Exception {
    // 계약 예시(서리풀 청년주택): 서울 거주·무주택·나이·소득이 맞아도 예술인 증명(ASK)은 직접 확인이라 확인 필요
    Judgment j = JudgmentReader.read(JudgmentReaderTest.example());
    var a =
        new ApplicantBuilder()
            .birth(1997, 3, 15)
            .region("11650")
            .homelessSince(2022, 1)
            .household(1)
            .personalIncome(30_000_000L)
            .build();

    var result = Judge.evaluate(j, a);

    var artist = result.targets().get(0);
    assertThat(artist.overall()).isEqualTo(Overall.NEEDS_INPUT);
    assertThat(artist.items())
        .filteredOn(i -> i.status() != Status.MET)
        .extracting(Item::key)
        .containsExactly("ASK");
  }

  @Test
  void overallRules() {
    assertThat(Overall.best(List.of(Overall.INELIGIBLE, Overall.NEEDS_INPUT)))
        .isEqualTo(Overall.NEEDS_INPUT);
    assertThat(Overall.best(List.of())).isEqualTo(Overall.INELIGIBLE);
    assertThat(Status.any(List.of())).isEqualTo(Status.UNKNOWN);
    assertThat(Status.all(List.of(Status.MET, Status.UNKNOWN))).isEqualTo(Status.UNKNOWN);
  }
}
