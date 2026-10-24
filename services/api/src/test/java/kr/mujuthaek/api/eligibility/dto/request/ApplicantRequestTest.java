package kr.mujuthaek.api.eligibility.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.notice.NoticeFixtures;
import org.junit.jupiter.api.Test;

class ApplicantRequestTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  static ApplicantRequest sample() {
    return new ApplicantRequest(
        LocalDate.of(1997, 3, 15),
        "11440",
        true,
        1,
        0,
        List.of("YOUTH"),
        "SINGLE",
        null,
        0,
        YearMonth.of(2022, 1),
        null,
        new ApplicantRequest.SubscriptionRequest(YearMonth.of(2024, 3), 26),
        List.of(),
        32_000_000L,
        null,
        45_000_000L,
        null,
        15_000_000L,
        List.of("R2_INCOME"));
  }

  @Test
  void everyFieldMayBeEmpty() {
    var empty =
        new ApplicantRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    assertThat(VALIDATOR.validate(empty)).isEmpty();
    assertThat(empty.toApplicant()).isEqualTo(Applicant.empty());
  }

  @Test
  void readsTheApiDesignExampleJson() {
    String json =
        """
        {"birthDate":"1997-03-15","regionCode":"11440","householdHead":true,"householdSize":1,
         "dependents":0,"targetGroups":["YOUTH"],"marital":"SINGLE","marriedOn":null,"children":0,
         "homelessSince":"2022-01","subscription":{"joinedOn":"2024-03","payments":26},
         "welfareStatuses":[],"personalIncomeAnnualWon":32000000,"householdIncomeAnnualWon":null,
         "totalAssetsWon":45000000,"carValueWon":null,"availableCashWon":15000000,"rankChecks":["R2_INCOME"],
         "workplace":{"label":"여의도역","lat":37.5216,"lng":126.9243},"priorities":{"work":8}}""";

    assertThat(NoticeFixtures.JSON.readValue(json, ApplicantRequest.class)).isEqualTo(sample());
  }

  @Test
  void rejectsMalformedValuesByFieldName() {
    var bad =
        new ApplicantRequest(
            LocalDate.now().plusDays(1),
            "1144",
            null,
            0,
            -1,
            List.of("STUDENT"),
            "DIVORCED",
            null,
            null,
            null,
            null,
            new ApplicantRequest.SubscriptionRequest(null, -3),
            List.of("RICH"),
            -1L,
            null,
            null,
            null,
            null,
            List.of("R9"));

    Set<String> fields =
        VALIDATOR.validate(bad).stream()
            .map(v -> v.getPropertyPath().toString().replaceAll("\\[\\d+]\\.<.*>", ""))
            .collect(Collectors.toSet());
    assertThat(fields)
        .contains(
            "birthDate",
            "regionCode",
            "householdSize",
            "dependents",
            "targetGroups",
            "marital",
            "subscription.payments",
            "welfareStatuses",
            "personalIncomeAnnualWon",
            "rankChecks");
  }

  @Test
  void toStringHidesIncomeAssetsAndBirthDate() {
    String text = sample().toString();

    assertThat(text)
        .startsWith("ApplicantRequest[")
        .contains("regionCode=11440", "birthDate=***", "personalIncomeAnnualWon=***")
        .doesNotContain("32000000", "45000000", "15000000", "1997-03-15", "R2_INCOME");
  }

  @Test
  void singlePersonHouseholdIncomeIsThePersonalIncome() {
    assertThat(sample().toApplicant().householdIncome()).isEqualTo(32_000_000L);
  }
}
