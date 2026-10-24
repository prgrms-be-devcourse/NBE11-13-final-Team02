package kr.mujuthaek.api.eligibility.rule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import kr.mujuthaek.api.common.logging.SensitiveFields;

/**
 * 판정에 넣는 사용자 입력 (API 설계 "판정 입력"). 모든 칸은 비워도 되고(null), 비운 조건은 "확인 필요"로 판정해요.
 *
 * <p>서버는 이 값을 저장하지 않고 로그에도 남기지 않아요. toString은 소득·자산·생년월일 등을 가려요.
 *
 * @param regionCode 지금 사는 시군구 5자리
 * @param householdSize 세대원 수 (나 포함)
 * @param dependents 부양가족 수 (나 제외)
 * @param targetGroups 지금 상황 (UNIVERSITY_STUDENT, JOB_SEEKER, YOUTH, NEWLYWED)
 * @param marital SINGLE · MARRIED · ENGAGED
 * @param marriedOn 혼인신고 연월
 * @param homelessSince 무주택 시작 연월 (집이 있으면 null이고 homeOwner = true)
 * @param welfareStatuses BASIC_LIVELIHOOD 수급자 · NEAR_POOR 차상위 · SINGLE_PARENT 한부모
 * @param rankChecks 2·3순위 자기 확인 체크 키 (GET /eligibility/criteria가 줘요)
 */
public record Applicant(
    LocalDate birthDate,
    String regionCode,
    Boolean householdHead,
    Integer householdSize,
    Integer dependents,
    List<String> targetGroups,
    String marital,
    YearMonth marriedOn,
    Integer children,
    YearMonth homelessSince,
    Boolean homeOwner,
    Subscription subscription,
    List<String> welfareStatuses,
    Long personalIncomeAnnualWon,
    Long householdIncomeAnnualWon,
    Long totalAssetsWon,
    Long carValueWon,
    Long availableCashWon,
    List<String> rankChecks) {

  /** 청약통장 가입 연월과 인정 납입 횟수. */
  public record Subscription(YearMonth joinedOn, Integer payments) {}

  public static Applicant empty() {
    return new Applicant(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }

  /** 가구 연소득. 1인 가구면 가구 = 본인이라 본인 소득으로 채워요. */
  public Long householdIncome() {
    if (householdIncomeAnnualWon != null) {
      return householdIncomeAnnualWon;
    }
    return Integer.valueOf(1).equals(householdSize) ? personalIncomeAnnualWon : null;
  }

  @Override
  public String toString() {
    return SensitiveFields.mask(
        "Applicant[birthDate="
            + birthDate
            + ", regionCode="
            + regionCode
            + ", householdHead="
            + householdHead
            + ", householdSize="
            + householdSize
            + ", dependents="
            + dependents
            + ", targetGroups="
            + targetGroups
            + ", marital="
            + marital
            + ", marriedOn="
            + marriedOn
            + ", children="
            + children
            + ", homelessSince="
            + homelessSince
            + ", homeOwner="
            + homeOwner
            + ", subscription="
            + subscription
            + ", welfareStatuses="
            + welfareStatuses
            + ", personalIncomeAnnualWon="
            + personalIncomeAnnualWon
            + ", householdIncomeAnnualWon="
            + householdIncomeAnnualWon
            + ", totalAssetsWon="
            + totalAssetsWon
            + ", carValueWon="
            + carValueWon
            + ", availableCashWon="
            + availableCashWon
            + ", rankChecks="
            + rankChecks
            + "]");
  }
}
