package kr.mujuthaek.api.eligibility.service;

import java.util.List;
import kr.mujuthaek.api.eligibility.model.NoticeCandidate;
import kr.mujuthaek.api.eligibility.rule.Field;

/**
 * 목록 판정 후보: 이 분야의 정제된 열린 공고(접수예정·접수중)와 각 공고의 판정 틀.
 *
 * <p>구현은 {@link JudgmentCandidateReader}예요 (주거·사업공간은 notice, 창업지원은 benefit의 판정용 조회를 불러요). 테스트에서는 가짜
 * 후보로 바꿔 끼워요.
 */
public interface JudgmentCandidates {

  List<NoticeCandidate> openNotices(Field field);
}
