package kr.mujuthaek.api.notice.dto.response;

import java.time.LocalDate;
import java.util.UUID;

/** 공고 목록 한 줄. 값이 없으면 null로 내려가요. */
public record NoticeSummaryResponse(
    UUID id,
    String field,
    String title,
    String provider,
    String noticeType,
    String regionCode,
    String regionName,
    String status,
    LocalDate applyStart,
    LocalDate applyEnd,
    Integer dday,
    LocalDate postedOn) {}
