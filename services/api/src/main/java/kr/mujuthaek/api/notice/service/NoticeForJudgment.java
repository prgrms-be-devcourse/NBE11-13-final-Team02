package kr.mujuthaek.api.notice.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 판정용 공고 하나 (판정 담당 요청 「판정을 위한 데이터 가공 요청안」).
 *
 * @param version 공고 버전 (정정되면 올라가는 값)
 * @param applyStart 접수 시작일 (모르면 null)
 * @param applyEnd 접수 마감일 (상시거나 모르면 null)
 * @param regionCodes 공고가 걸친 지역의 법정동 10자리 (시군구 5자리 뒤를 0으로 채움). 전국 공고는 빈 목록, 지역을 모르면(위치 지역 보류·코드 없음)
 *     null — 빈 목록으로 주면 모든 지역이 충족돼요
 * @param frame 현재 공개 버전의 판정 틀 그대로 (housing-judgment.v1 / business-space-judgment.v1). 없으면 null
 */
public record NoticeForJudgment(
    UUID noticeId,
    int version,
    String title,
    LocalDate applyStart,
    LocalDate applyEnd,
    List<String> regionCodes,
    JsonNode frame) {}
