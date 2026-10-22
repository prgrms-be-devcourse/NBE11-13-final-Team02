package kr.mujuthaek.api.notice.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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
import kr.mujuthaek.api.notice.mapper.NoticeContent;
import kr.mujuthaek.api.notice.mapper.NoticeDetailMapper;
import kr.mujuthaek.api.notice.repository.AreaScoreRepository;
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

  /** 공고 상세 API 경로 (NoticeController). */
  static final String DETAIL_PATH = "/api/v1/notices/";

  /** 요약 한 번에 묻는 공고 수. */
  private static final int SUMMARY_CHUNK = 1000;

  private final NoticeQueries queries;
  private final FollowUpRepository followUps;
  private final AreaScoreRepository areaScores;
  private final RegionQueryService regions;
  private final Clock clock;
  private final JsonMapper json;

  public NoticeQueryService(
      NoticeQueries queries,
      FollowUpRepository followUps,
      AreaScoreRepository areaScores,
      RegionQueryService regions,
      Clock clock,
      JsonMapper json) {
    this.queries = queries;
    this.followUps = followUps;
    this.areaScores = areaScores;
    this.regions = regions;
    this.clock = clock;
    this.json = json;
  }

  public CursorPageResponse<NoticeSummaryResponse> list(
      NoticeListFilter filter, String cursor, Integer size) {
    CursorQuery page = CursorQuery.of(cursor, size, filter.sort());
    LocalDate today = LocalDate.now(clock);
    var rows = queries.list(filter, page.after(), page.fetchSize());
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
            row.status(),
            row.applyStart(),
            row.applyEnd(),
            NoticeStatus.dday(row.applyEnd(), today),
            row.regionCode(),
            regions.displayName(regionLookup(row.regionCode())),
            row.postedOn(),
            current.detailUrl(),
            row.needsCheck());
    JsonNode payload = payload(current);
    // 지금 버전에서 보류한 위치의 점수는 빼요(위치 내용은 같아도 보류 항목만 늘어난 버전이면 이전 점수가 남아 있어요)
    Set<String> scored = NoticeContent.locationIds(payload.path("notice"));
    return NoticeDetailMapper.map(
        header,
        payload,
        areaScores.findByNotice(row.id()).stream()
            .filter(s -> scored.contains(s.locationId()))
            .map(
                s ->
                    new NoticeDetailResponse.AreaSummary(
                        s.locationId(),
                        s.kind(),
                        s.score(),
                        s.calculatedAt().atZone(clock.getZone()).toOffsetDateTime()))
            .toList(),
        followUps.findByNotice(row.id()).stream()
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
              now.kind(),
              row.reviewStatus(),
              before == null ? List.of() : now.changedFrom(before),
              local(row.receivedAt())));
      before = now;
    }
    return out;
  }

  /**
   * 판정용 공고 목록: 이 분야의 열린 공고 전부를 한 번에 줘요(판정 담당 요청). 접수예정·접수중·상시만 넣어요(판정 담당 「열린 공고」 기준). 일정을 단정할 수 없는
   * 확인 필요(UNKNOWN)는 지원할 수 있다고 보여 주지 않게 빼요. 판정 틀이 아직 없는 공고도 빼지 않고 frame을 null로 줘요.
   */
  public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
    List<NoticeForJudgment> notices =
        queries.openForJudgment(field.name()).stream()
            .map(
                row ->
                    new NoticeForJudgment(
                        row.id(),
                        row.version(),
                        row.title(),
                        row.applyStart(),
                        row.applyEnd(),
                        regionCodes(row.nationwide(), row.regionCodes()),
                        row.frameJson() == null ? null : json.readTree(row.frameJson())))
            .toList();
    if (field != NoticeField.BUSINESS_SPACE) {
      return notices;
    }
    // 사업공간 모집(창업지원 API의 입주기업 모집)도 사업공간 판정 대상이에요. noticeId 자리에 정책 ID(rawPolicyId)를 줘요
    List<NoticeForJudgment> spaces =
        queries.openPolicies("SPACE_RECRUITMENT", LocalDate.now(clock)).stream()
            .map(
                row ->
                    new NoticeForJudgment(
                        row.rawPolicyId(),
                        row.version(),
                        row.title(),
                        row.applyStart(),
                        row.applyEnd(),
                        legalDongs(row.regionCodes()),
                        json.readTree(row.frameJson())))
            .toList();
    return java.util.stream.Stream.concat(notices.stream(), spaces.stream()).toList();
  }

  /**
   * 판정용 창업지원 사업 목록(판정 담당 요청): 마감되지 않은 창업지원 사업 전부를 한 번에 줘요. 판정 틀이 있는 사업만 저장되므로 frame은 늘 있어요.
   * programId는 수집 DB 정책 ID(rawPolicyId)예요.
   */
  public List<ProgramForJudgment> findOpenProgramsForJudgment() {
    return queries.openPolicies("STARTUP_SUPPORT", LocalDate.now(clock)).stream()
        .map(
            row ->
                new ProgramForJudgment(
                    row.rawPolicyId(),
                    row.version(),
                    row.title(),
                    row.applyStart(),
                    row.applyEnd(),
                    legalDongs(row.regionCodes()),
                    json.readTree(row.frameJson())))
        .toList();
  }

  /**
   * 공고 요약 여러 개를 한 번에 (알림 담당 요청: 공고 ID로 제목·마감일 등). 물은 ID → 요약이며 물은 순서를 지켜요. 다른 공고에 합쳐진 공고 ID는 상세
   * API처럼 합친(남은) 공고의 요약을 줘요(값의 noticeId가 물은 ID와 달라요). 숨김·공개 전·없는 공고는 빠져요. 마감된 공고도 줘요.
   */
  public java.util.Map<UUID, NoticeSummary> findSummaries(java.util.Collection<UUID> ids) {
    List<UUID> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    java.util.Map<UUID, NoticeSummary> found = new java.util.HashMap<>();
    // IN 목록이 너무 길어지지 않게 나눠 물어요 (보통은 한 번)
    for (int from = 0; from < distinct.size(); from += SUMMARY_CHUNK) {
      List<UUID> chunk = distinct.subList(from, Math.min(from + SUMMARY_CHUNK, distinct.size()));
      for (var row : queries.summaries(chunk)) {
        found.put(row.requestedId(), summary(row));
      }
    }
    java.util.Map<UUID, NoticeSummary> ordered = new java.util.LinkedHashMap<>();
    for (UUID id : distinct) {
      if (found.containsKey(id)) {
        ordered.put(id, found.get(id));
      }
    }
    return ordered;
  }

  /** 공고 요약 하나 (findSummaries와 같은 규칙). 숨김·공개 전·없는 공고면 빈 값. */
  public java.util.Optional<NoticeSummary> findSummary(UUID noticeId) {
    return java.util.Optional.ofNullable(findSummaries(List.of(noticeId)).get(noticeId));
  }

  private static NoticeSummary summary(NoticeQueries.SummaryRow row) {
    return new NoticeSummary(
        row.id(),
        row.version(),
        row.field(),
        row.title(),
        row.provider(),
        row.noticeType(),
        row.status(),
        row.applyStart(),
        row.applyEnd(),
        regionCodes(row.nationwide(), row.regionCodes()),
        row.nationwide(),
        DETAIL_PATH + row.id(),
        row.detailUrl());
  }

  /** 판정 목록과 같은 지역 뜻: 전국이면 빈 목록, 지역 색인이 비면(모름) null, 아니면 법정동 10자리. */
  private static List<String> regionCodes(boolean nationwide, List<String> sigungu) {
    if (nationwide) {
      return List.of();
    }
    return sigungu.isEmpty() ? null : legalDongs(sigungu);
  }

  /** 시군구 5자리 → 법정동 10자리 (뒤를 0으로 채움). */
  private static List<String> legalDongs(List<String> sigungu) {
    return sigungu.stream().map(code -> code + "00000").toList();
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
        row.status(),
        row.applyStart(),
        row.applyEnd(),
        NoticeStatus.dday(row.applyEnd(), today),
        row.postedOn(),
        row.needsCheck());
  }

  /** 시도 단위 코드(11000)는 시도 이름으로 찾아요. */
  static String regionLookup(String regionCode) {
    if (regionCode != null && regionCode.endsWith("000")) {
      return regionCode.substring(0, 2);
    }
    return regionCode;
  }
}
