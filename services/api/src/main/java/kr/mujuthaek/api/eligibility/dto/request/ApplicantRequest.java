package kr.mujuthaek.api.eligibility.dto.request;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.mujuthaek.api.eligibility.rule.frame.Applicant.ApplicantType;
import kr.mujuthaek.api.eligibility.rule.frame.Applicant.MaritalStatus;

/**
 * 사용자 입력 (세 분야 같이 써요). 모두 선택 입력이에요. 칸 뜻은 rule/frame/Applicant를 봐요.
 *
 * <p>저장하거나 로그에 남기지 않아요. 잘못된 값이 와도 오류 메시지에 그 값을 넣지 않아요.
 */
public record ApplicantRequest(
    LocalDate birthDate,
    Boolean korean,
    String residenceRegionCode,
    Integer residenceMonths,
    MaritalStatus maritalStatus,
    LocalDate marriedOn,
    List<LocalDate> childBirthDates,
    Boolean expectingChild,
    HouseholdRequest household,
    SubscriptionRequest subscription,
    BusinessRequest business,
    List<String> statuses,
    List<String> restrictions) {

  public record HouseholdRequest(
      Integer size,
      Boolean head,
      Boolean homeless,
      Boolean applicantHomeless,
      Integer homelessYears,
      Long monthlyIncomeWon,
      Long applicantMonthlyIncomeWon,
      Long parentsMonthlyIncomeWon,
      Integer applicantAndParentsSize,
      Boolean dualIncome,
      Long totalAssetWon,
      Long applicantTotalAssetWon,
      Long carValueWon) {}

  public record SubscriptionRequest(Integer months, Integer payments, Long balanceWon) {}

  public record BusinessRequest(
      ApplicantType applicantType,
      Boolean hasSite,
      String siteRegionCode,
      List<BusinessRecordRequest> history,
      String industryCode,
      Map<Integer, Long> revenueByYear) {}

  public record BusinessRecordRequest(LocalDate openedOn, LocalDate closedOn) {}
}
