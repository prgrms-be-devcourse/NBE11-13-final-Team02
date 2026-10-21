package kr.mujuthaek.api.eligibility.service;

import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.dto.response.NoticeEvaluationResponse;
import kr.mujuthaek.api.eligibility.mapper.JudgmentReader;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.eligibility.rule.Judge;
import kr.mujuthaek.api.eligibility.rule.Overall;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.springframework.stereotype.Service;

/**
 * 공고 판정: notice가 가진 판정 틀(extraction이 만든 것)을 판정 입력으로 판정하고, 주택형별 준비할 돈을 계산해요. 입력과 결과는 저장하지 않아요.
 *
 * <p>판정 틀이 없는 공고(검수 대기, 예전에 처리된 공고 등)는 확인 필요로 답해요.
 */
@Service
public class NoticeEvaluationService {

  private final NoticeQueryService notices;
  private final CriteriaService criteria;
  private final PrepareCostService prepareCost;

  public NoticeEvaluationService(
      NoticeQueryService notices, CriteriaService criteria, PrepareCostService prepareCost) {
    this.notices = notices;
    this.criteria = criteria;
    this.prepareCost = prepareCost;
  }

  public NoticeEvaluationResponse evaluate(UUID noticeId, Applicant applicant) {
    var source = notices.judgingSource(noticeId);
    Overall overall;
    String reason = null;
    List<Judge.Target> targets = List.of();
    List<String> missing = List.of();
    if (source.judgment() == null) {
      overall = Overall.NEEDS_INPUT;
      reason = "이 공고는 아직 자격 판정을 준비하지 못했어요. 공고 원문의 자격 조건을 확인해 주세요";
    } else {
      Judge.Result result =
          Judge.evaluate(JudgmentReader.read(source.judgment()), applicant, criteria.benchmarks());
      overall = result.overall();
      targets = result.targets();
      missing = result.missingInputs();
    }
    var prepare = prepareCost.preparer(applicant);
    boolean eligible = overall == Overall.ELIGIBLE;
    var unitTypes =
        source.unitTypes().stream()
            .map(
                u ->
                    new NoticeEvaluationResponse.UnitType(
                        u.houseType(), eligible, prepare.apply(u.depositWon())))
            .toList();
    return new NoticeEvaluationResponse(noticeId, overall, reason, targets, missing, unitTypes);
  }
}
