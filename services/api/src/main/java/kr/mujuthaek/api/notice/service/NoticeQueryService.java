package kr.mujuthaek.api.notice.service;

import java.time.Clock;
import java.time.LocalDate;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.common.pagination.CursorKey;
import kr.mujuthaek.api.common.pagination.CursorQuery;
import kr.mujuthaek.api.common.region.RegionQueryService;
import kr.mujuthaek.api.notice.domain.NoticeStatus;
import kr.mujuthaek.api.notice.dto.request.NoticeListFilter;
import kr.mujuthaek.api.notice.dto.response.NoticeSummaryResponse;
import kr.mujuthaek.api.notice.repository.NoticeQueries;
import kr.mujuthaek.api.notice.repository.NoticeQueries.ListRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 누구나 보는 공고 조회 (목록·상세·주택목록·정정 이력). 다른 모듈도 이 서비스로 공고를 조회해요. */
@Service
@Transactional(readOnly = true)
public class NoticeQueryService {

  private final NoticeQueries queries;
  private final RegionQueryService regions;
  private final Clock clock;

  public NoticeQueryService(NoticeQueries queries, RegionQueryService regions, Clock clock) {
    this.queries = queries;
    this.regions = regions;
    this.clock = clock;
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
