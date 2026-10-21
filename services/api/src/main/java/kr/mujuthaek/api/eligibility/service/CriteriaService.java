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
