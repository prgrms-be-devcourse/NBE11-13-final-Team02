package kr.mujuthaek.api.notice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 공고 상세 (API 설계 "상세 응답의 묶음"). 현재 공개 버전의 추출 결과에서 만들어요. 첨부는 기관 링크만 주고 파일은 주지 않아요.
 *
 * @param followUps 원래 공고에 붙은 후속 안내 (서류 제출·당첨자 발표 등). 공고 버전과 따로 붙어요
 * @param hasUnits 호별 주택목록이 있는지 (없으면 화면이 주택목록·지도 섹션을 숨겨요)
 * @param reviewed 검수자가 확인한 버전인지
 */
public record NoticeDetailResponse(
    Header header,
    Summary summary,
    List<Schedule> schedules,
    List<Site> sites,
    List<UnitType> unitTypes,
    List<Condition> conditions,
    List<FileLink> files,
    List<FollowUp> followUps,
    boolean hasUnits,
    int version,
    boolean reviewed,
    OffsetDateTime updatedAt) {

  public record Header(
      UUID id,
      String field,
      String title,
      String provider,
      String noticeType,
      String supplyTypeText,
      String status,
      LocalDate applyStart,
      LocalDate applyEnd,
      Integer dday,
      String regionCode,
      String regionName,
      LocalDate postedOn,
      String detailUrl) {}

  /**
   * @param supplyTotal 모집 호수 (공고에 적힌 수, 없으면 주택형별 합)
   */
  public record Summary(
      String summaryText,
      List<String> targetGroups,
      Integer supplyTotal,
      BigDecimal exclusiveM2Min,
      BigDecimal exclusiveM2Max) {}

  /**
   * @param current 오늘이 이 일정 안이면 true (화면의 지금 단계 표시)
   */
  public record Schedule(
      String id,
      String label,
      List<String> kinds,
      String startsAt,
      String endsAt,
      String description,
      String applicationUrl,
      boolean current) {}

  public record Site(String id, String kind, String name, String address, String regionCode) {}

  public record UnitType(
      String houseType,
      List<String> targetGroups,
      BigDecimal exclusiveM2,
      Long depositWon,
      Long rentWon,
      Integer supplyCount) {}

  /** 대상별 자격 조건과 근거 원문. 판정은 POST /eligibility/notices/{id}/evaluate로 해요. */
  public record Condition(
      String id,
      String category,
      String target,
      List<String> targetGroups,
      String description,
      String originalText,
      boolean inReview) {}

  public record FileLink(String name, String url) {}

  /** 후속 안내 글 하나와 그 일정. */
  public record FollowUp(
      String title, String detailUrl, LocalDate postedOn, List<Schedule> schedules) {}
}
