package kr.mujuthaek.api.area.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 공공 상가 위치의 상권 점수 (7단계, 요청 때 계산·저장 안 함). API 문서 GET /areas/commercial/{siteId}의 응답이에요.
 *
 * @param stores300m 300m 안 점포 수
 * @param sameIndustry300m 300m 안 같은 업종 점포 수 (같은 업종이 없는 업종이면 null)
 * @param industryGroup 같은 업종 묶음 (없으면 null)
 * @param nearestStation 가장 가까운 도시철도역 (역 자료가 없으면 null)
 * @param households 단지 세대 수 — 공고 자료라 area는 몰라요(늘 null, 화면이 공고 상세에서 가져와요)
 * @param scores DENSITY(밀도)·COMPETITION(경쟁, 같은 업종이 없거나 점포가 0이면 빠짐)·TRANSIT(교통) 0~100
 * @param score 총점 0~100 (항목 평균, 0.5 올림)
 */
public record CommercialScore(
    UUID siteId,
    long stores300m,
    Long sameIndustry300m,
    CommercialIndustry industryGroup,
    NearestStation nearestStation,
    Integer households,
    Map<String, Integer> scores,
    int score,
    Instant calculatedAt) {}
