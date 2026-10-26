package kr.mujuthaek.api.eligibility.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.eligibility.model.JudgmentResult;

/**
 * 사업공간 하나의 판정 (POST /spaces/{id}/evaluate).
 *
 * @param policy 사업공간 모집 정책이면 true. 공고 상세(GET /notices/{id})가 없어서 화면은 이 응답과 원문으로 보여줘요
 * @param source 모집 정책의 출처 KSTARTUP · BIZINFO. 공고·모르면 null
 * @param regionCodes 지역 법정동코드 10자리. 전국은 빈 목록, 모르면 null
 * @param status UPCOMING · APPLYING · ALWAYS (모집 정책은 기간으로 정해요)
 * @param judgment 자격 판정. 판정 틀이 아직 없으면 null (화면은 "자격 조건 정리 중")
 */
public record SpaceEvaluationResponse(
    UUID id,
    boolean policy,
    String source,
    String title,
    String detailUrl,
    LocalDate applyStart,
    LocalDate applyEnd,
    Integer dday,
    String status,
    List<String> regionCodes,
    JudgmentResult judgment) {}
