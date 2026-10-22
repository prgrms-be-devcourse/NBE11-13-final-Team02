package kr.mujuthaek.api.eligibility.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 목록 판정 후보 공고 하나 (주거·사업공간은 notice, 창업지원은 benefit의 지원사업). 판정 틀과, 응답에 그대로 옮길 공고 정보예요.
 *
 * @param regionCodes 공고 지역 법정동코드 10자리 (표준 공고의 regionCodes 그대로). 판정에는 쓰지 않고 응답에 옮겨서 프론트가 지역으로 걸러요.
 *     전국 공고·지역을 못 읽은 공고는 빈 목록
 * @param frame 판정 틀 (housing-judgment.v1 등). 아직 가공 전이면 null
 */
public record NoticeCandidate(
    UUID noticeId,
    int version,
    String title,
    LocalDate applyStart,
    LocalDate applyEnd,
    List<String> regionCodes,
    JsonNode frame) {

  public NoticeCandidate {
    regionCodes = regionCodes == null ? List.of() : List.copyOf(regionCodes);
  }
}
