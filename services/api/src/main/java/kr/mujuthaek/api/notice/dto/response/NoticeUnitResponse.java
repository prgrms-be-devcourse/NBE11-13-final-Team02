package kr.mujuthaek.api.notice.dto.response;

import java.math.BigDecimal;
import java.util.List;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse.Evidence;

/**
 * 주택목록 한 호. 좌표는 area가 주소로 만들면 붙어요(아직 없음).
 *
 * @param evidence 보증금·월임대료의 근거 원문
 */
public record NoticeUnitResponse(
    String id,
    String siteId,
    String address,
    String unitLabel,
    String floor,
    String houseType,
    BigDecimal exclusiveM2,
    Long depositWon,
    Long rentWon,
    List<Evidence> evidence) {}
