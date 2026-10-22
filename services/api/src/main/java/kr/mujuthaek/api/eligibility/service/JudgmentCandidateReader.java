package kr.mujuthaek.api.eligibility.service;

import java.util.List;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Field;
import kr.mujuthaek.api.notice.service.NoticeField;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.springframework.stereotype.Component;

/**
 * 목록 판정 후보를 분야마다 맡은 모듈에서 가져와요. 주거·사업공간은 notice의 공고({@link NoticeQueryService#findOpenForJudgment}),
 * 창업지원은 benefit의 지원사업({@link BenefitQueryService#findOpenProgramsForJudgment})이에요.
 */
@Component
class JudgmentCandidateReader implements JudgmentCandidates {

  private final NoticeQueryService notices;
  private final BenefitQueryService benefits;

  JudgmentCandidateReader(NoticeQueryService notices, BenefitQueryService benefits) {
    this.notices = notices;
    this.benefits = benefits;
  }

  @Override
  public List<NoticeCandidate> openNotices(Field field) {
    return switch (field) {
      case HOUSING -> notices(NoticeField.HOUSING);
      case BUSINESS_SPACE -> notices(NoticeField.BUSINESS_SPACE);
      case STARTUP_SUPPORT -> programs();
    };
  }

  private List<NoticeCandidate> notices(NoticeField field) {
    return notices.findOpenForJudgment(field).stream()
        .map(
            n ->
                new NoticeCandidate(
                    n.noticeId(),
                    n.version(),
                    n.title(),
                    n.applyStart(),
                    n.applyEnd(),
                    n.regionCodes(),
                    n.frame()))
        .toList();
  }

  private List<NoticeCandidate> programs() {
    return benefits.findOpenProgramsForJudgment().stream()
        .map(
            p ->
                new NoticeCandidate(
                    p.programId(),
                    p.version(),
                    p.title(),
                    p.applyStart(),
                    p.applyEnd(),
                    p.regionCodes(),
                    p.frame()))
        .toList();
  }
}
