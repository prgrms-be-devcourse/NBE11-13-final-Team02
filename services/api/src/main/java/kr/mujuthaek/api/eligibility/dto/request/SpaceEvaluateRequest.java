package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;

/** 사업공간 하나의 판정 요청 (POST /spaces/{id}/evaluate). 비우면 필요한 입력(missingInputs)을 알려줘요. */
public record SpaceEvaluateRequest(@Valid JudgmentApplicantRequest applicant) {}
