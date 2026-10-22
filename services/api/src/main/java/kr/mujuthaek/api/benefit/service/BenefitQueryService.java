package kr.mujuthaek.api.benefit.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 혜택·지원사업 조회. 다른 모듈은 이 서비스의 조회 메서드로만 혜택·지원사업을 읽어요. */
@Service
@Transactional(readOnly = true)
public class BenefitQueryService {

  /**
   * 판정용 창업지원 사업 목록: 신청예정·신청중인 사업 전부를 한 번에 줘요. 판정 틀이 아직 없는 사업도 빼지 않고 frame을 null로 줘요.
   *
   * <p>TODO(유태민): 지원사업·판정 틀 저장이 생기면 채워요. 지금은 빈 목록이에요 (2026-10-22 이석우가 판정에 필요한 모양으로 먼저 만든 자리).
   */
  public List<ProgramForJudgment> findOpenProgramsForJudgment() {
    return List.of();
  }
}
