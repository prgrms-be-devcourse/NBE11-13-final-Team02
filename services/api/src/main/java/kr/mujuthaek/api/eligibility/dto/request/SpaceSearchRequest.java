package kr.mujuthaek.api.eligibility.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.rule.frame.Answer;

/**
 * 사업공간 맞춤 목록 요청 (POST /spaces/search). 판정 입력이 섞여서 POST예요.
 *
 * @param applicant 판정 입력 (사업 입력은 business). 비우면 모든 공간이 확인 필요예요
 * @param sort RECOMMENDED(기본: 지원 가능 → 확인 필요 → 지원 불가, 같으면 마감 임박) · DEADLINE(마감 임박)
 * @param answers 공간 ID → 질문(판정 결과 questions[].id) → YES · NO. 상세에서 답한 것을 목록 판정에도 써요. 저장하지 않아요
 * @param includeIneligible 지원 불가 공간도 보여줄지 (기본 false)
 */
public record SpaceSearchRequest(
    @Valid Filters filters,
    @Valid JudgmentApplicantRequest applicant,
    Map<UUID, Map<String, Answer>> answers,
    @Pattern(regexp = "RECOMMENDED|DEADLINE") String sort,
    Boolean includeIneligible,
    String cursor,
    @Min(1) @Max(100) Integer size) {

  /**
   * @param spaceTypes STARTUP_SPACE(창업 공간) · SHOP_RENTAL(공공 상가) · MEETING_ROOM(회의실). 비우면 전부
   * @param regionCodes 시도 2자리 또는 시군구 5자리. 비우면 전국. 전국 공간과 지역을 모르는 공간은 늘 들어가요
   * @param keyword 검색어. 띄어쓰기로 나눈 낱말이 모두 이름(제목)·기관에 들어 있는 것만. 비우면 전부
   */
  public record Filters(
      List<@Pattern(regexp = "STARTUP_SPACE|SHOP_RENTAL|MEETING_ROOM") String> spaceTypes,
      List<@Pattern(regexp = "\\d{2}|\\d{5}") String> regionCodes,
      @Size(max = 100) String keyword) {}

  public Filters filtersOrEmpty() {
    return filters == null ? new Filters(null, null, null) : filters;
  }

  public String sortOrDefault() {
    return sort == null ? "RECOMMENDED" : sort;
  }
}
