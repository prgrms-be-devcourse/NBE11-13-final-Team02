package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import java.util.Map;
import kr.mujuthaek.api.eligibility.rule.frame.Answer;

/**
 * 사업공간·사업 지원 하나의 판정 요청 (POST /spaces/{id}/evaluate, /supports/{id}/evaluate). 비우면 필요한
 * 입력(missingInputs)과 질문(questions)을 알려줘요.
 *
 * @param answers 질문(판정 결과 questions[].id) → YES · NO. 답하지 않은 질문은 확인 필요. 저장하지 않아요
 */
public record SpaceEvaluateRequest(
    @Valid JudgmentApplicantRequest applicant, Map<String, Answer> answers) {}
