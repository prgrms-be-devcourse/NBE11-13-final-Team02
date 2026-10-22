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
    List<String> restrictions,
    LoanRequest loan) {

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

  /**
   * 대출용 소득·자산·생애최초 (대출 자격 판정에만 써요). 주택도시기금 상품안내 기준이라 판정의 월소득·총자산과 따로 받아요.
   *
   * @param annualIncomeWon 부부합산 연소득 (미혼이면 본인)
   * @param applicantAnnualIncomeWon 본인(대출신청인) 연소득
   * @param netAssetWon 부부합산 순자산 (자산 − 부채, 음수일 수 있어요)
   * @param firstHomePurchase 세대원 모두 집을 산 적이 없어서 생애최초 주택구입이면 true (구입 대출에 써요)
   */
  public record LoanRequest(
      Long annualIncomeWon,
      Long applicantAnnualIncomeWon,
      Long netAssetWon,
      Boolean firstHomePurchase) {}
}
