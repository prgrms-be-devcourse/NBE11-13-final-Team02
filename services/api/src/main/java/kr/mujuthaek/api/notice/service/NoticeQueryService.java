package kr.mujuthaek.api.notice.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.common.pagination.CursorKey;
import kr.mujuthaek.api.common.pagination.CursorQuery;
import kr.mujuthaek.api.common.region.RegionQueryService;
import kr.mujuthaek.api.notice.domain.CompletedNotice;
import kr.mujuthaek.api.notice.domain.NoticeStatus;
import kr.mujuthaek.api.notice.dto.request.NoticeListFilter;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeSummaryResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeUnitResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeVersionResponse;
import kr.mujuthaek.api.notice.mapper.NoticeDetailMapper;
import kr.mujuthaek.api.notice.repository.NoticeQueries;
import kr.mujuthaek.api.notice.repository.NoticeQueries.CurrentRow;
import kr.mujuthaek.api.notice.repository.NoticeQueries.ListRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 누구나 보는 공고 조회 (목록·상세·주택목록·정정 이력). 다른 모듈도 이 서비스로 공고를 조회해요. */
@Service
@Transactional(readOnly = true)
public class NoticeQueryService {

  private static final String UNITS_SORT = "UNITS";

  private final NoticeQueries queries;
  private final RegionQueryService regions;
  private final Clock clock;
  private final JsonMapper json;

  public NoticeQueryService(
      NoticeQueries queries, RegionQueryService regions, Clock clock, JsonMapper json) {
    this.queries = queries;
    this.regions = regions;
    this.clock = clock;
    this.json = json;
  }

  public CursorPageResponse<NoticeSummaryResponse> list(
      NoticeListFilter filter, String cursor, Integer size) {
    CursorQuery page = CursorQuery.of(cursor, size, filter.sort());
    LocalDate today = LocalDate.now(clock);
    var rows = queries.list(filter, today, page.after(), page.fetchSize());
    return CursorPageResponse.from(
        rows,
        page.size(),
        row -> summary(row, today),
        row -> CursorKey.of(filter.sort(), NoticeQueries.sortValue(row, filter.sort()), row.id()));
  }

  /** 상세. 없거나 숨김·공개 전이면 404 NOTICE_NOT_FOUND. */
  public NoticeDetailResponse detail(UUID noticeId) {
    CurrentRow current = current(noticeId);
    LocalDate today = LocalDate.now(clock);
    ListRow row = current.row();
    var header =
        new NoticeDetailResponse.Header(
            row.id(),
            row.field(),
            row.title(),
            row.provider(),
            row.noticeType(),
            current.supplyTypeText(),
            NoticeStatus.of(row.applyStart(), row.applyEnd(), today).name(),
            row.applyStart(),
            row.applyEnd(),
            NoticeStatus.dday(row.applyEnd(), today),
            row.regionCode(),
            regions.displayName(regionLookup(row.regionCode())),
            row.postedOn(),
            current.detailUrl());
    return NoticeDetailMapper.map(
        header,
        payload(current),
        current.version(),
        current.reviewed(),
        local(current.updatedAt()),
        today);
  }

  /**
   * 주택목록 (호별). 목록 안 위치를 커서로 써요. 주택목록이 없는 공고는 빈 목록이에요.
   *
   * @param siteId 단지·건물 하나만 볼 때
   */
  public CursorPageResponse<NoticeUnitResponse> units(
      UUID noticeId, String siteId, String cursor, Integer size) {
    CursorQuery page = CursorQuery.of(cursor, size, UNITS_SORT);
    var all = NoticeDetailMapper.units(payload(current(noticeId)).path("notice"), siteId);
    int from = page.after() == null ? 0 : position(page.after().value()) + 1;
    List<Integer> positions =
        java.util.stream.IntStream.range(Math.min(from, all.size()), all.size())
            .limit(page.fetchSize())
            .boxed()
            .toList();
    return CursorPageResponse.from(
        positions, page.size(), all::get, i -> CursorKey.of(UNITS_SORT, i, noticeId));
  }

  /** 정정 이력 (버전, 글 종류, 검수 상태, 바뀐 항목, 받은 시각). 없거나 숨김·공개 전이면 404. */
  public List<NoticeVersionResponse> versions(UUID noticeId) {
    var rows = queries.versions(noticeId);
    if (rows.isEmpty()) {
      throw new CustomException(ErrorCode.NOTICE_NOT_FOUND);
    }
    List<NoticeVersionResponse> out = new java.util.ArrayList<>();
    CompletedNotice before = null;
    for (var row : rows) {
      CompletedNotice now = CompletedNotice.from(json.readTree(row.payload()));
      out.add(
          new NoticeVersionResponse(
              row.version(),
              row.kind(),
              row.reviewStatus(),
              before == null ? List.of() : now.changedFrom(before),
              local(row.receivedAt())));
      before = now;
    }
    return out;
  }

  /**
   * 판정에 쓰는 공고 내용 (eligibility가 불러요).
   *
   * @param judgment 판정 틀 (contracts/schemas/housing-judgment.v1.json). 아직 없으면 null
   * @param unitTypes 주택형별 보증금·월임대료 (준비할 돈 계산)
   */
  public record JudgingSource(
      UUID noticeId,
      String field,
      String noticeType,
      JsonNode judgment,
      List<UnitPrice> unitTypes) {}

  public record UnitPrice(
      String houseType, java.math.BigDecimal exclusiveM2, Long depositWon, Long rentWon) {}

  /** 없거나 숨김·공개 전이면 404 NOTICE_NOT_FOUND. */
  public JudgingSource judgingSource(UUID noticeId) {
    CurrentRow current = current(noticeId);
    JsonNode payload = payload(current);
    JsonNode judgment = payload.path("judgment");
    var units =
        NoticeDetailMapper.unitTypes(payload.path("notice")).stream()
            .map(u -> new UnitPrice(u.houseType(), u.exclusiveM2(), u.depositWon(), u.rentWon()))
            .toList();
    return new JudgingSource(
        noticeId,
        current.row().field(),
        current.row().noticeType(),
        judgment.isObject() ? judgment : null,
        units);
  }

  /** API의 시각은 서울 시간(+09:00)이에요. DB에서는 UTC로 읽혀요. */
  private OffsetDateTime local(OffsetDateTime time) {
    return time == null ? null : time.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
  }

  private static int position(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new CustomException(ErrorCode.INVALID_CURSOR, ErrorCode.INVALID_CURSOR.message());
    }
  }

  CurrentRow current(UUID noticeId) {
    return queries
        .current(noticeId)
        .orElseThrow(() -> new CustomException(ErrorCode.NOTICE_NOT_FOUND));
  }

  JsonNode payload(CurrentRow current) {
    return json.readTree(current.payload());
  }

  private NoticeSummaryResponse summary(ListRow row, LocalDate today) {
    return new NoticeSummaryResponse(
        row.id(),
        row.field(),
        row.title(),
        row.provider(),
        row.noticeType(),
        row.regionCode(),
        regions.displayName(regionLookup(row.regionCode())),
        NoticeStatus.of(row.applyStart(), row.applyEnd(), today).name(),
        row.applyStart(),
        row.applyEnd(),
        NoticeStatus.dday(row.applyEnd(), today),
        row.postedOn());
  }

  /** 시도 단위 코드(11000)는 시도 이름으로 찾아요. */
  static String regionLookup(String regionCode) {
    if (regionCode != null && regionCode.endsWith("000")) {
      return regionCode.substring(0, 2);
    }
    return regionCode;
  }
}
