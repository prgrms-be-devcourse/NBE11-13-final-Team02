package kr.mujuthaek.api.notice.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 판정용 공고 하나 (eligibility가 목록 판정에 써요).
 *
 * @param regionCodes 표준 공고(standard-notice.v1)의 regionCodes 그대로. 법정동코드 10자리, 전국 공고는 빈 목록
 * @param frame 판정 틀 (housing·business-space·startup-support-judgment.v1). 아직 가공 전이면 null
 */
public record NoticeForJudgment(
    UUID noticeId,
    int version,
    String title,
    LocalDate applyStart,
    LocalDate applyEnd,
    List<String> regionCodes,
    JsonNode frame) {}
