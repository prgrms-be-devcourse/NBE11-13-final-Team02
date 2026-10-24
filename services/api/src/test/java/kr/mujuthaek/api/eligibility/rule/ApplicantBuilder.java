package kr.mujuthaek.api.eligibility.rule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 테스트용 판정 입력. 넣지 않은 칸은 비어 있어요(null). */
public final class ApplicantBuilder {

  private LocalDate birthDate;
  private String regionCode;
  private Boolean householdHead;
  private Integer householdSize;
  private Integer dependents;
  private List<String> targetGroups;
  private String marital;
  private YearMonth marriedOn;
  private Integer children;
  private YearMonth homelessSince;
  private Boolean homeOwner;
  private Applicant.Subscription subscription;
  private List<String> welfareStatuses;
  private Long personalIncome;
  private Long householdIncome;
  private Long assets;
  private Long car;
  private Long cash;
  private List<String> rankChecks;

  public ApplicantBuilder birth(int y, int m, int d) {
    birthDate = LocalDate.of(y, m, d);
    return this;
  }

  public ApplicantBuilder region(String code) {
    regionCode = code;
    return this;
  }

  public ApplicantBuilder head(boolean value) {
    householdHead = value;
    return this;
  }

  public ApplicantBuilder household(int size) {
    householdSize = size;
    return this;
  }

  public ApplicantBuilder dependents(int n) {
    dependents = n;
    return this;
  }

  public ApplicantBuilder targetGroups(String... groups) {
    targetGroups = List.of(groups);
    return this;
  }

  public ApplicantBuilder marital(String value) {
    marital = value;
    return this;
  }

  public ApplicantBuilder married(int y, int m) {
    marital = "MARRIED";
    marriedOn = YearMonth.of(y, m);
    return this;
  }

  public ApplicantBuilder children(int n) {
    children = n;
    return this;
  }

  public ApplicantBuilder homelessSince(int y, int m) {
    homelessSince = YearMonth.of(y, m);
    return this;
  }

  public ApplicantBuilder homeOwner(boolean value) {
    homeOwner = value;
    return this;
  }

  public ApplicantBuilder subscription(int y, int m, int payments) {
    subscription = new Applicant.Subscription(YearMonth.of(y, m), payments);
    return this;
  }

  public ApplicantBuilder welfare(String... statuses) {
    welfareStatuses = List.of(statuses);
    return this;
  }

  public ApplicantBuilder personalIncome(long annualWon) {
    personalIncome = annualWon;
    return this;
  }

  public ApplicantBuilder householdIncome(long annualWon) {
    householdIncome = annualWon;
    return this;
  }

  public ApplicantBuilder assets(long won) {
    assets = won;
    return this;
  }

  public ApplicantBuilder car(long won) {
    car = won;
    return this;
  }

  public ApplicantBuilder cash(long won) {
    cash = won;
    return this;
  }

  public ApplicantBuilder rankChecks(String... keys) {
    rankChecks = List.of(keys);
    return this;
  }

  public Applicant build() {
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
        subscription,
        welfareStatuses,
        personalIncome,
        householdIncome,
        assets,
        car,
        cash,
        rankChecks);
  }
}
