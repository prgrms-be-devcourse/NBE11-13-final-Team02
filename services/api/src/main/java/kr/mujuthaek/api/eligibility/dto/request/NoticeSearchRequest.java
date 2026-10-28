package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 맞춤 목록 요청 (POST /notices/search). 판정 입력이 섞여서 GET이 아니라 POST예요.
 *
 * @param sort RECOMMENDED(기본: 지원 가능 → 확인 필요 → 마감 임박 → 생활권 총점) · DEADLINE · PREPARE_COST(준비할 돈 적은 순)
 *     · AREA_SCORE(생활권 총점 높은 순)
 * @param includeIneligible 지원 불가 공고도 보여줄지 (기본 false)
 */
public record NoticeSearchRequest(
    @Valid Filters filters,
    @Valid ApplicantRequest applicant,
    @Pattern(regexp = "RECOMMENDED|DEADLINE|PREPARE_COST|AREA_SCORE") String sort,
    Boolean includeIneligible,
    String cursor,
    @Min(1) @Max(100) Integer size) {

  /**
   * 공개 목록(GET /notices)과 같은 필터. statuses를 비우면 접수예정·접수중이에요.
   *
   * @param keyword 검색어. 띄어쓰기로 나눈 낱말이 모두 제목에 들어 있는 공고만. 비우면 전부
   */
  public record Filters(
      String field,
      List<String> regionCodes,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses,
      @Size(max = 100) String keyword) {}

  public Filters filtersOrEmpty() {
    return filters == null ? new Filters(null, null, null, null, null, null, null) : filters;
  }

  public String sortOrDefault() {
    return sort == null ? "RECOMMENDED" : sort;
  }
}
