package kr.mujuthaek.api.notice.dto.response;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 공고 목록 한 줄. 값이 없으면 null로 내려가요.
 *
 * @param status 접수 상태. UNKNOWN은 일정이 없거나 확실하지 않아 단정할 수 없음(화면의 「확인 필요」)
 * @param needsCheck 원문 확인 전까지 보여 주지 않는 항목이 있음 (화면의 「확인 필요」 표시)
 */
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
    LocalDate postedOn,
    boolean needsCheck) {}
