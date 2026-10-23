package kr.mujuthaek.api.area.geocode;

import java.util.List;
import java.util.Optional;

/**
 * 검색 결과 중 공고 주소에 맞는 하나 고르기. 하나로 정해지지 않으면 고르지 않아요(엉뚱한 단지에 좌표를 주는 것보다 못 찾는 게 나아요).
 *
 * <p>순서: 결과가 하나면 그것 → 모두 같은 건물 출입구(시군구·도로명·지하·번호)면 첫 번째 → 받은 법정동코드(10자리)와 같은 것이 하나 → 받은 시군구(5자리)와
 * 같은 것이 하나.
 */
public final class JusoCandidates {

  private JusoCandidates() {}

  /**
   * @param bjdCode 공고에서 받은 법정동코드 10자리 (없으면 null)
   * @param sigunguCode 주소에서 판별한 시군구 코드 5자리 (없으면 null)
   */
  public static Optional<JusoResult> pick(
      List<JusoResult> results, String bjdCode, String sigunguCode) {
    if (results.isEmpty()) {
      return Optional.empty();
    }
    if (results.size() == 1 || sameEntrance(results)) {
      return Optional.of(results.getFirst());
    }
    if (bjdCode != null) {
      Optional<JusoResult> one =
          only(results.stream().filter(r -> bjdCode.equals(r.admCd())).toList());
      if (one.isPresent()) {
        return one;
      }
    }
    if (sigunguCode != null) {
      return only(results.stream().filter(r -> sigunguCode.equals(r.sigunguCode())).toList());
    }
    return Optional.empty();
  }

  private static boolean sameEntrance(List<JusoResult> results) {
    String first = results.getFirst().roadKey();
    return results.stream().allMatch(r -> r.roadKey().equals(first));
  }

  private static Optional<JusoResult> only(List<JusoResult> matches) {
    if (matches.size() == 1 || (!matches.isEmpty() && sameEntrance(matches))) {
      return Optional.of(matches.getFirst());
    }
    return Optional.empty();
  }
}
