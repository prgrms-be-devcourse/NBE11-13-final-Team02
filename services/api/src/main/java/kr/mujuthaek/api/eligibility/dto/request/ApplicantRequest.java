package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import kr.mujuthaek.api.common.logging.SensitiveFields;
import kr.mujuthaek.api.eligibility.rule.Applicant;

/**
 * 판정 요청 본문의 {@code applicant} (API 설계 "판정 입력"). 모든 칸은 비워도 돼요. 형식만 검사하고 값은 어디에도 남기지 않아요.
 *
 * <p>연월(marriedOn, homelessSince, subscription.joinedOn)은 "2025-04" 모양이에요. workplace·priorities는
 * 생활권 점수용이라 판정에서는 쓰지 않아요(받기는 해요).
 */
public record ApplicantRequest(
    @Past LocalDate birthDate,
    @Pattern(regexp = "[0-9]{5}") String regionCode,
    Boolean householdHead,
    @Min(1) @Max(20) Integer householdSize,
    @Min(0) @Max(20) Integer dependents,
    List<@Pattern(regexp = "UNIVERSITY_STUDENT|JOB_SEEKER|YOUTH|NEWLYWED") String> targetGroups,
    @Pattern(regexp = "SINGLE|MARRIED|ENGAGED") String marital,
    YearMonth marriedOn,
    @Min(0) @Max(20) Integer children,
    YearMonth homelessSince,
    Boolean homeOwner,
    @Valid SubscriptionRequest subscription,
    List<@Pattern(regexp = "BASIC_LIVELIHOOD|NEAR_POOR|SINGLE_PARENT") String> welfareStatuses,
    @PositiveOrZero Long personalIncomeAnnualWon,
    @PositiveOrZero Long householdIncomeAnnualWon,
    @PositiveOrZero Long totalAssetsWon,
    @PositiveOrZero Long carValueWon,
    @PositiveOrZero Long availableCashWon,
    List<@Pattern(regexp = "R[23]_[A-Z_]+") String> rankChecks) {

  public record SubscriptionRequest(YearMonth joinedOn, @Min(0) @Max(1000) Integer payments) {}

  public Applicant toApplicant() {
    return new Applicant(
        birthDate,
        regionCode,
        householdHead,
        householdSize,
        dependents,
        targetGroups,
        marital,
        marriedOn,
        children,
        homelessSince,
        homeOwner,
        subscription == null
            ? null
            : new Applicant.Subscription(subscription.joinedOn(), subscription.payments()),
        welfareStatuses,
        personalIncomeAnnualWon,
        householdIncomeAnnualWon,
        totalAssetsWon,
        carValueWon,
        availableCashWon,
        rankChecks);
  }

  @Override
  public String toString() {
    return SensitiveFields.mask(
        "ApplicantRequest" + toApplicant().toString().substring("Applicant".length()));
  }
}
