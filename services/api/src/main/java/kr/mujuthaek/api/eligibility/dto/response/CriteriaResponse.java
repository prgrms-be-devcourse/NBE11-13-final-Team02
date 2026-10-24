package kr.mujuthaek.api.eligibility.dto.response;

import java.time.LocalDate;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 판정 기준 금액 (GET /eligibility/criteria). 조건 입력 화면의 "총자산가액 34,500만 원 이하" 같은 문구에 써요.
 *
 * @param medianIncomeMonthlyWon 가구원 수("1"·"2" …)별 기준 중위소득 월액. 그해 값이 없으면 null
 * @param rankCriteria 순위 요건 [{ rank, rule(ANY·ALL), items: [{ key, label, … }] }]. 그해 값이 없으면 null
 */
public record CriteriaResponse(
    int year,
    LocalDate validFrom,
    Map<String, Long> medianIncomeMonthlyWon,
    JsonNode rankCriteria) {}
