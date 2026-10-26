package kr.mujuthaek.api.eligibility.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.model.JudgmentResult;

/**
 * 창업지원 사업 하나의 판정 (POST /supports/{id}/evaluate). 사업 상세 API가 없어서 화면은 이 응답과 원문으로 보여줘요.
 *
 * @param source 출처 KSTARTUP · BIZINFO. 모르면 null
 * @param subField FUND · LOAN · TAX (이름으로 정해요)
 * @param regionCodes 지역 법정동코드 10자리. 전국은 빈 목록, 모르면 null
 * @param status UPCOMING · APPLYING · ALWAYS
 * @param judgment 자격 판정. 판정 틀이 아직 없으면 null
 */
public record SupportEvaluationResponse(
    UUID id,
    String source,
    String subField,
    String title,
    String detailUrl,
    LocalDate applyStart,
    LocalDate applyEnd,
    Integer dday,
    String status,
    List<String> regionCodes,
    JudgmentResult judgment) {}
