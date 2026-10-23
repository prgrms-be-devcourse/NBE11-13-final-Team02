package kr.mujuthaek.api.eligibility.mapper;

import java.util.List;
import kr.mujuthaek.api.eligibility.model.MatchResult;
import kr.mujuthaek.api.eligibility.model.MissingInput;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Eligibility;
import kr.mujuthaek.api.eligibility.rule.Field;
import kr.mujuthaek.api.eligibility.rule.Verdict;
import kr.mujuthaek.api.eligibility.rule.frame.FrameJudgment;

/** 공고 하나의 판정 → 목록 결과 한 줄, 정렬된 줄 → 목록 결과 (목록 판정 서비스가 불러요). */
public final class MatchMapper {

  public static final String CONDITIONS_NOT_READY = "CONDITIONS_NOT_READY";

  private MatchMapper() {}

  /** 정렬을 마친 줄로 목록 결과를 만들어요 (개수는 줄에서 세요). */
  public static MatchResult toResult(Field field, List<MatchResult.Item> items) {
    return new MatchResult(
        field.name(),
        new MatchResult.Counts(
            count(items, Eligibility.ELIGIBLE),
            count(items, Eligibility.CHECK_NEEDED),
            (int) items.stream().filter(i -> CONDITIONS_NOT_READY.equals(i.reason())).count()),
        items);
  }

  /** 판정 틀이 없거나 읽지 못한 공고: 확인 필요 + 자격 조건 정리 중. */
  public static MatchResult.Item notReady(NoticeCandidate n) {
    return item(n, Eligibility.CHECK_NEEDED, null, CONDITIONS_NOT_READY, List.of(), List.of(), 0);
  }

  /** 판정한 공고 (지원 가능·확인 필요만 불러요). */
  public static MatchResult.Item judged(NoticeCandidate n, FrameJudgment j) {
    boolean checkNeeded = j.result() == Eligibility.CHECK_NEEDED;
    return item(
        n,
        j.result(),
        j.rank(),
        null,
        groups(j, checkNeeded ? Verdict.UNKNOWN : Verdict.MET),
        checkNeeded ? JudgmentMapper.missingInputs(j.missingInputs()) : List.of(),
        checkNeeded ? openQuestions(j) : 0);
  }

  private static MatchResult.Item item(
      NoticeCandidate n,
      Eligibility status,
      Integer rank,
      String reason,
      List<String> groups,
      List<MissingInput> missingInputs,
      int openQuestions) {
    return new MatchResult.Item(
        n.noticeId(),
        n.version(),
        n.title(),
        n.regionCodes(),
        n.applyStart(),
        n.applyEnd(),
        status,
        rank,
        reason,
        groups,
        missingInputs,
        openQuestions);
  }

  private static int count(List<MatchResult.Item> items, Eligibility status) {
    return (int) items.stream().filter(i -> i.status() == status).count();
  }

  private static List<String> groups(FrameJudgment j, Verdict verdict) {
    return j.groups().stream()
        .filter(g -> g.verdict() == verdict)
        .map(g -> g.group().name())
        .toList();
  }

  private static int openQuestions(FrameJudgment j) {
    return (int)
        j.questions().stream()
            .filter(q -> q.purpose() == FrameJudgment.Purpose.ELIGIBILITY && q.answer() == null)
            .count();
  }
}
