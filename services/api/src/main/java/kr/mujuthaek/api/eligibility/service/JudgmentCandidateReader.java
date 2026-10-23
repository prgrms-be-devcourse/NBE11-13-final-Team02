package kr.mujuthaek.api.eligibility.service;

import java.util.List;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Field;
import kr.mujuthaek.api.notice.service.NoticeField;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.springframework.stereotype.Component;

/**
 * 목록 판정 후보를 notice의 판정용 조회({@link NoticeQueryService#findOpenForJudgment})로 가져와요. 주거·사업공간·창업지원 세 분야
 * 모두 notice가 줘요.
 */
@Component
class JudgmentCandidateReader implements JudgmentCandidates {

  private final NoticeQueryService notices;

  JudgmentCandidateReader(NoticeQueryService notices) {
    this.notices = notices;
  }

  @Override
  public List<NoticeCandidate> openNotices(Field field) {
    return notices.findOpenForJudgment(noticeField(field)).stream()
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

  private static NoticeField noticeField(Field field) {
    return switch (field) {
      case HOUSING -> NoticeField.HOUSING;
      case STARTUP_SUPPORT -> NoticeField.STARTUP_SUPPORT;
      case BUSINESS_SPACE -> NoticeField.BUSINESS_SPACE;
    };
  }
}
