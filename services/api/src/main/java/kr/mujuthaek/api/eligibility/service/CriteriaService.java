package kr.mujuthaek.api.eligibility.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.eligibility.dto.response.CriteriaResponse;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore;
import kr.mujuthaek.api.eligibility.repository.CriteriaStore.CriteriaRow;
import kr.mujuthaek.api.eligibility.rule.IncomeBenchmarks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 연도별 판정 기준값. 판정(소득 기준 금액)과 조건 입력 화면이 같이 써요. */
@Service
@Transactional(readOnly = true)
public class CriteriaService {

  private final CriteriaStore store;
  private final JsonMapper json;
  private final Clock clock;

  public CriteriaService(CriteriaStore store, JsonMapper json, Clock clock) {
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  /**
   * @param year 비우면 오늘 쓰는 가장 최근 연도
   * @param noticeType 공고 유형별 순위 요건이 있으면 그것 (RANK_CRITERIA의 byNoticeType)
   */
  public CriteriaResponse criteria(Integer year, String noticeType) {
    int y =
        year != null
            ? year
            : store
                .latestYear(LocalDate.now(clock))
                .orElseThrow(() -> new CustomException(ErrorCode.CRITERIA_NOT_FOUND));
    Optional<CriteriaRow> median = store.find("MEDIAN_INCOME", y);
    Optional<CriteriaRow> ranks = store.find("RANK_CRITERIA", y);
    if (median.isEmpty() && ranks.isEmpty()) {
      throw new CustomException(ErrorCode.CRITERIA_NOT_FOUND);
    }
    LocalDate validFrom = median.or(() -> ranks).map(CriteriaRow::validFrom).orElseThrow();
    return new CriteriaResponse(
        y,
        validFrom,
        median.map(r -> monthly(json.readTree(r.data()))).orElse(null),
        ranks.map(r -> rankCriteria(json.readTree(r.data()), noticeType)).orElse(null));
  }

  /** 가구원 수별 월 금액 (판정의 소득 기준). 그해 값이 없으면 빈 값. */
  public Map<String, Long> monthlyIncome(String kind, int year) {
    return store.find(kind, year).map(r -> monthly(json.readTree(r.data()))).orElse(Map.of());
  }

  /**
   * 판정 엔진에 넘길 기준 소득 월액. 기준 중위소득은 MEDIAN_INCOME, 도시근로자 소득(두 이름)은 URBAN_WORKER_INCOME 값을 써요. 한 번의 판정
   * 안에서 같은 연도를 여러 번 읽지 않게 기억해 둬요.
   */
  public IncomeBenchmarks benchmarks() {
    Map<String, Map<String, Long>> cache = new java.util.HashMap<>();
    return (standard, year, householdSize) -> {
      String kind = "MEDIAN_INCOME".equals(standard) ? "MEDIAN_INCOME" : "URBAN_WORKER_INCOME";
      Map<String, Long> table = cache.computeIfAbsent(kind + year, k -> monthlyIncome(kind, year));
      return table.get(String.valueOf(householdSize));
    };
  }

  private static Map<String, Long> monthly(JsonNode data) {
    Map<String, Long> out = new LinkedHashMap<>();
    data.path("monthlyWon").properties().forEach(e -> out.put(e.getKey(), e.getValue().asLong()));
    return out;
  }

  private static JsonNode rankCriteria(JsonNode data, String noticeType) {
    JsonNode byType = data.path("byNoticeType").path(noticeType == null ? "" : noticeType);
    return byType.isMissingNode() ? data.path("ranks") : byType;
  }
}
