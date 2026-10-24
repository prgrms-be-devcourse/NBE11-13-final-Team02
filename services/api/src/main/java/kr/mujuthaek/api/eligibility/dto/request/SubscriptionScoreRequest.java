package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 청약 가점 계산 요청. applicant에서
 * birthDate·marital·marriedOn·homelessSince·homeOwner·dependents·subscription.joinedOn을 써요.
 *
 * @param referenceDate 기준일 (모집공고일). 비우면 오늘
 */
public record SubscriptionScoreRequest(
    @Valid @NotNull ApplicantRequest applicant, LocalDate referenceDate) {}
