package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.rule.frame.Answer;

/**
 * 사업 지원(창업지원 사업) 맞춤 목록 요청 (POST /supports/search). 판정 입력이 섞여서 POST예요.
 *
 * @param applicant 판정 입력 (사업 입력은 business). 비우면 모든 사업이 확인 필요예요
 * @param sort RECOMMENDED(기본: 지원 가능 → 확인 필요 → 지원 불가, 같으면 마감 임박) · DEADLINE(마감 임박)
 * @param answers 사업 ID → 질문(판정 결과 questions[].id) → YES · NO. 상세에서 답한 것을 목록 판정에도 써요. 저장하지 않아요
 * @param includeIneligible 지원 불가 사업도 보여줄지 (기본 false)
 */
public record SupportSearchRequest(
    @Valid Filters filters,
    @Valid JudgmentApplicantRequest applicant,
    Map<UUID, Map<String, Answer>> answers,
    @Pattern(regexp = "RECOMMENDED|DEADLINE") String sort,
    Boolean includeIneligible,
    String cursor,
    @Min(1) @Max(100) Integer size) {

  /**
   * @param subFields FUND(지원금) · LOAN(정책자금) · TAX(세금 감면). 비우면 전부
   * @param regionCodes 시도 2자리 또는 시군구 5자리. 비우면 전국. 전국 사업과 지역을 모르는 사업은 늘 들어가요
   */
  public record Filters(
      List<@Pattern(regexp = "FUND|LOAN|TAX") String> subFields,
      List<@Pattern(regexp = "\\d{2}|\\d{5}") String> regionCodes) {}

  public Filters filtersOrEmpty() {
    return filters == null ? new Filters(null, null) : filters;
  }

  public String sortOrDefault() {
    return sort == null ? "RECOMMENDED" : sort;
  }
}
