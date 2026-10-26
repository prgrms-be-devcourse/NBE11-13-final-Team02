package kr.mujuthaek.api.eligibility.dto.response;

import java.util.UUID;
import kr.mujuthaek.api.eligibility.model.JudgmentResult;

/**
 * 사업공간 하나의 판정 (POST /spaces/{id}/evaluate).
 *
 * @param policy 사업공간 모집 정책이면 true (상세는 detailUrl 원문)
 * @param judgment 자격 판정. 판정 틀이 아직 없으면 null (화면은 "자격 조건 정리 중")
 */
public record SpaceEvaluationResponse(
    UUID id, boolean policy, String title, String detailUrl, JudgmentResult judgment) {}
