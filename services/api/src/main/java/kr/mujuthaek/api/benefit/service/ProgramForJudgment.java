package kr.mujuthaek.api.benefit.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 판정용 창업지원 사업 하나 (eligibility가 목록 판정에 써요).
 *
 * @param version 사업 공고 버전 (정정되면 올라가는 값)
 * @param applyEnd 신청 마감일. 상시면 null
 * @param regionCodes 지원 지역 법정동코드 10자리. 전국 사업은 빈 목록
 * @param frame 판정 틀 (startup-support-judgment.v1). 아직 가공 전이면 null
 */
public record ProgramForJudgment(
    UUID programId,
    int version,
    String title,
    LocalDate applyStart,
    LocalDate applyEnd,
    List<String> regionCodes,
    JsonNode frame) {}
