package kr.mujuthaek.api.notice.dto.request;

import java.util.List;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notice.domain.NoticeStatus;
import kr.mujuthaek.api.notice.domain.NoticeType;

/**
 * 공고 목록 필터 (GET /notices 쿼리). 목록 값은 쉼표로 이어도 되고 같은 이름을 여러 번 줘도 돼요.
 *
 * @param field HOUSING(기본) · BUSINESS_SPACE
 * @param regionCodes 시군구 5자리 또는 시도 2자리
 * @param tenure RENT 빌려서 · BUY 사서 (공고 유형에서 정해요: 공공·민영 분양이 BUY)
 * @param statuses 비우면 마감(CLOSED)을 뺀 전부
 * @param sort DEADLINE(기본) 마감 임박 · LATEST 최신
 */
public record NoticeListFilter(
    String field,
    List<String> regionCodes,
    String tenure,
    List<String> noticeTypes,
    List<String> providers,
    List<String> statuses,
    String sort) {

  public static final List<String> BUY_TYPES =
      List.of(NoticeType.PUBLIC_SALE.name(), NoticeType.PRIVATE_SALE.name());

  public NoticeListFilter {
    field = field == null ? "HOUSING" : field;
    sort = sort == null ? "DEADLINE" : sort;
    regionCodes = regionCodes == null ? List.of() : regionCodes;
    noticeTypes = noticeTypes == null ? List.of() : noticeTypes;
    providers = providers == null ? List.of() : providers;
    statuses =
        statuses == null || statuses.isEmpty()
            ? List.of(
                NoticeStatus.UPCOMING.name(),
                NoticeStatus.APPLYING.name(),
                NoticeStatus.ALWAYS.name())
            : statuses;
    check(List.of(field), List.of("HOUSING", "BUSINESS_SPACE"), "field");
    check(List.of(sort), List.of("DEADLINE", "LATEST"), "sort");
    check(tenure == null ? List.of() : List.of(tenure), List.of("RENT", "BUY"), "tenure");
    check(noticeTypes, names(NoticeType.values()), "noticeTypes");
    check(statuses, names(NoticeStatus.values()), "statuses");
    for (String code : regionCodes) {
      if (!code.matches("[0-9]{2}([0-9]{3})?")) {
        throw invalid("regionCodes는 시군구 5자리 또는 시도 2자리예요");
      }
    }
  }

  public List<String> sigunguCodes() {
    return regionCodes.stream().filter(c -> c.length() == 5).toList();
  }

  public List<String> sidoCodes() {
    return regionCodes.stream().filter(c -> c.length() == 2).toList();
  }

  private static void check(List<String> values, List<String> allowed, String name) {
    for (String v : values) {
      if (!allowed.contains(v)) {
        throw invalid(name + "에 쓸 수 없는 값이에요 (가능: " + String.join(", ", allowed) + ")");
      }
    }
  }

  private static List<String> names(Enum<?>[] values) {
    return java.util.Arrays.stream(values).map(Enum::name).toList();
  }

  private static CustomException invalid(String detail) {
    return new CustomException(ErrorCode.VALIDATION_FAILED, detail);
  }
}
