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
import kr.mujuthaek.api.notice.repository.FollowUpRepository;
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
  private final FollowUpRepository followUps;
  private final RegionQueryService regions;
  private final Clock clock;
  private final JsonMapper json;

  public NoticeQueryService(
      NoticeQueries queries,
      FollowUpRepository followUps,
      RegionQueryService regions,
      Clock clock,
      JsonMapper json) {
    this.queries = queries;
    this.followUps = followUps;
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
        followUps.findByNotice(noticeId).stream()
            .map(
                f ->
                    NoticeDetailMapper.followUp(
                        f.title(),
                        f.detailUrl(),
                        f.postedOn(),
                        json.readTree(f.schedulesJson()),
                        today))
            .toList(),
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
    int from = page.after() == null ? 0 : page.after().position() + 1;
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
              now.kind(),
              row.reviewStatus(),
              before == null ? List.of() : now.changedFrom(before),
              local(row.receivedAt())));
      before = now;
    }
    return out;
  }

  /** 맞춤 목록 후보 한 줄 (eligibility가 판정해 정렬해요). 목록 한 줄과 같은 값이에요. */
  public record Candidate(
      UUID id,
      String field,
      String title,
      String provider,
      String noticeType,
      String regionCode,
      String regionName,
      String status,
      LocalDate applyStart,
      LocalDate applyEnd,
      Integer dday,
      LocalDate postedOn) {}

  /**
   * 맞춤 목록(POST /notices/search)의 후보: 필터에 맞는 공개 공고를 마감 임박순으로 최대 limit개. 판정은 계산값이라 먼저 필터로 줄여요.
   *
   * @param statuses 비우면 접수예정·접수중
   */
  public List<Candidate> searchCandidates(
      String field,
      List<String> regionCodes,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses,
      int limit) {
    var filter =
        new NoticeListFilter(
            field,
            regionCodes,
            tenure,
            noticeTypes,
            providers,
            statuses, // 비우면 공개 목록(GET /notices)과 같은 기본값 (상시 공고 ALWAYS 포함)
            "DEADLINE");
    LocalDate today = LocalDate.now(clock);
    return queries.list(filter, today, null, limit).stream()
        .map(row -> summary(row, today))
        .map(
            s ->
                new Candidate(
                    s.id(),
                    s.field(),
                    s.title(),
                    s.provider(),
                    s.noticeType(),
                    s.regionCode(),
                    s.regionName(),
                    s.status(),
                    s.applyStart(),
                    s.applyEnd(),
                    s.dday(),
                    s.postedOn()))
        .toList();
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

  /**
   * 판정용 공고 목록: 이 분야의 열린 공고(접수예정·접수중) 전부를 한 번에 줘요. 판정 틀이 아직 없는 공고도 빼지 않고 frame을 null로 줘요.
   *
   * <p>TODO(권상록): 공고·판정 틀 저장이 생기면 채워요. 지금은 빈 목록이에요 (2026-10-21 이석우가 합의한 모양으로 먼저 만든 자리). 공고 판정 API는
   * {@link #judgingSource}로 판정 틀을 읽어요.
   */
  public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
    return List.of();
  }

  /**
   * 판정용 창업지원 사업 목록: 마감되지 않은 창업지원 사업 전부를 한 번에 줘요. programId는 수집 DB 정책 ID(rawPolicyId)예요.
   *
   * <p>TODO(권상록): #70에서 채워요. 지금은 빈 목록이에요 (2026-10-23 이석우가 #70과 같은 모양으로 먼저 만든 자리).
   */
  public List<ProgramForJudgment> findOpenProgramsForJudgment() {
    return List.of();
  }
}
