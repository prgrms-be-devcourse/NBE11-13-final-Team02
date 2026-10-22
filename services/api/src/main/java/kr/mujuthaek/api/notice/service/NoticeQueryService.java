package kr.mujuthaek.api.notice.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공고 조회. 다른 모듈은 이 서비스의 조회 메서드로만 공고를 읽어요. */
@Service
@Transactional(readOnly = true)
public class NoticeQueryService {

  /**
   * 판정용 공고 목록: 이 분야의 열린 공고(접수예정·접수중) 전부를 한 번에 줘요. 판정 틀이 아직 없는 공고도 빼지 않고 frame을 null로 줘요.
   *
   * <p>TODO(권상록): 공고·판정 틀 저장이 생기면 채워요. 지금은 빈 목록이에요 (2026-10-21 이석우가 합의한 모양으로 먼저 만든 자리).
   */
  public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
    return List.of();
  }
}
