package kr.mujuthaek.api.common.event;

import java.util.List;
import java.util.UUID;

/**
 * 새 공고가 등록됨. notification(사용자 알림)이 받아요. reprocess면 사용자 알림을 보내지 않아요. 지역 점수 계산(area)은 위치가 담긴
 * NoticeLocationsChanged를 받아요.
 *
 * @param regionCode 대표 지역 5자리 하나: 한 시군구면 그 코드, 한 시도 안의 여러 시군구면 시도 코드(xx000), 여러 시도에 걸치면 null (알림
 *     대상은 regionCodes로 고르세요)
 * @param regionCodes 공고가 걸친 모든 지역 5자리 (공고 검색 색인과 같은 값). 여러 시도에 걸친 공고도 모두 담아요(예: 대구·경북 → 27000,
 *     47000). 보류한 위치의 지역은 빠져요. 지역을 모르면 빈 목록
 * @param nationwide 전국 대상 공고면 true (어느 지역 사용자에게도 해당)
 */
public record NoticePublished(
    UUID noticeId,
    String field,
    String regionCode,
    List<String> regionCodes,
    boolean nationwide,
    String title,
    boolean reprocess) {}
