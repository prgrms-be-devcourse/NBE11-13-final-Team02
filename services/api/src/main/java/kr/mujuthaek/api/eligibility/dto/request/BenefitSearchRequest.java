package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 혜택 맞춤 목록 요청 (POST /benefits/search). 판정 입력이 섞여서 POST예요. 판정 입력은 저장하지 않아요.
 *
 * @param sort RECOMMENDED(기본: 지원 가능 → 1년에 받는 돈 많은 순 → 마감 임박) · DEADLINE · AMOUNT
 */
public record BenefitSearchRequest(
    @Valid Filters filters,
    @Valid ApplicantRequest applicant,
    @Pattern(regexp = "RECOMMENDED|DEADLINE|AMOUNT") String sort,
    String cursor,
    @Min(1) @Max(100) Integer size) {

  /**
   * @param regionCodes 찾아볼 지역 (시도 2자리 · 시군구 5자리). 비우면 전체, 있으면 전국 혜택 + 그 지역 혜택
   * @param categories HOUSING · ASSET · TRANSPORT. 비우면 전체
   */
  public record Filters(
      List<@Pattern(regexp = "[0-9]{2}|[0-9]{5}") String> regionCodes,
      List<@Pattern(regexp = "HOUSING|ASSET|TRANSPORT") String> categories) {}

  public List<String> regionCodes() {
    return filters == null || filters.regionCodes() == null ? List.of() : filters.regionCodes();
  }

  public List<String> categories() {
    return filters == null || filters.categories() == null ? List.of() : filters.categories();
  }

  public String sortOrDefault() {
    return sort == null ? "RECOMMENDED" : sort;
  }
}
