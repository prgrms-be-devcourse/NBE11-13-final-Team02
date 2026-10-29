package kr.mujuthaek.api.notice.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujuthaek.api.area.service.NoticeAreaQueryService;
import kr.mujuthaek.api.area.service.NoticeAreaQueryService.NoticeSite;
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
import kr.mujuthaek.api.notice.dto.response.NoticeMapResponse;
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
  private final NoticeAreaQueryService areas;
  private final Clock clock;
  private final JsonMapper json;

  public NoticeQueryService(
      NoticeQueries queries,
      FollowUpRepository followUps,
      AreaScoreRepository areaScores,
      RegionQueryService regions,
      NoticeAreaQueryService areas,
      Clock clock,
      JsonMapper json) {
    this.queries = queries;
    this.followUps = followUps;
    this.areaScores = areaScores;
    this.regions = regions;
    this.areas = areas;
    this.clock = clock;
    this.json = json;
  }

  public CursorPageResponse<NoticeSummaryResponse> list(
      NoticeListFilter filter, String cursor, Integer size) {
    CursorQuery page = CursorQuery.of(cursor, size, filter.sort());
    LocalDate today = LocalDate.now(clock);
    var rows = queries.list(filter, page.after(), page.fetchSize());
    var sites = areas.sitesOf(rows.stream().map(ListRow::id).toList());
    var names = regionNames(rows);
    return CursorPageResponse.from(
        rows,
        page.size(),
        row -> summary(row, today, sites.getOrDefault(row.id(), List.of()), names),
        row -> CursorKey.of(filter.sort(), NoticeQueries.sortValue(row, filter.sort()), row.id()));
  }

  /** 상세. 없거나 숨김·공개 전이면 404 NOTICE_NOT_FOUND. 위치(sites)에는 area의 siteId·좌표·세권 태그를 붙여요. */
  public NoticeDetailResponse detail(UUID noticeId) {
    NoticeDetailResponse detail = detailWithoutArea(noticeId);
    var area =
        areas
            .sitesOf(List.of(detail.header().id()))
            .getOrDefault(detail.header().id(), List.of())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    NoticeSite::noticeLocationId, s -> s, (a, b) -> a));
    return detail.withSites(
        detail.sites().stream()
            .map(
                site -> {
                  NoticeSite a = site.id() == null ? null : area.get(site.id());
                  return a == null ? site : site.withArea(a.siteId(), a.lat(), a.lng(), a.tags());
                })
            .toList());
  }

  private NoticeDetailResponse detailWithoutArea(UUID noticeId) {
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
      LocalDate postedOn,
      Double lat,
      Double lng,
      int siteCount,
      Integer areaScore,
      List<String> tags) {}

  /**
   * 맞춤 목록(POST /notices/search)의 후보: 필터에 맞는 공개 공고를 마감 임박순으로 최대 limit개. 판정은 계산값이라 먼저 필터로 줄여요.
   *
   * @param statuses 비우면 공개 목록과 같은 기본값 (마감 CLOSED를 뺀 전부)
   * @param keyword 검색어. 낱말이 모두 제목에 들어 있는 공고만. 비우면 전부
   */
  public List<Candidate> searchCandidates(
      String field,
      List<String> regionCodes,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses,
      String keyword,
      int limit) {
    var filter =
        new NoticeListFilter(
            field,
            regionCodes,
            tenure,
            noticeTypes,
            providers,
            statuses, // 비우면 공개 목록(GET /notices)과 같은 기본값 (상시 ALWAYS·확인 필요 UNKNOWN 포함)
            "DEADLINE",
            null,
            null,
            keyword);
    LocalDate today = LocalDate.now(clock);
    var rows = queries.list(filter, null, limit);
    var sites = areas.sitesOf(rows.stream().map(ListRow::id).toList());
    var names = regionNames(rows);
    return rows.stream()
        .map(row -> summary(row, today, sites.getOrDefault(row.id(), List.of()), names))
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
                    s.postedOn(),
                    s.lat(),
                    s.lng(),
                    s.siteCount(),
                    s.areaScore(),
                    s.tags()))
        .toList();
  }

  /**
   * 판정용 공고 목록: 이 분야의 열린 공고 전부를 한 번에 줘요(판정 담당 요청). 접수예정·접수중·상시만 넣어요(판정 담당 「열린 공고」 기준). 일정을 단정할 수 없는
   * 확인 필요(UNKNOWN)는 지원할 수 있다고 보여 주지 않게 빼요. 판정 틀이 아직 없는 공고도 빼지 않고 frame을 null로 줘요. 사업공간이면 사업공간 모집
   * 정책도 policy=true로 붙여요(마감일을 모르는데 상시도 아닌 정책·검수 대기 틀은 빼요, openPolicies).
   */
  /**
   * 정책의 출처 안내 (K-Startup · 기업마당 API 칸을 옮긴 것, AI 값 아님). 판정 틀에 없는 지원 분야 · 요약 · 문의처 · 접수 방법을 화면에 보여줘요.
   *
   * @param amountText 요약 글의 「최대 · 한도 N원」 구절 (없으면 null)
   * @param applyNote 마감일 대신 쓰는 신청 기간 안내 (「예산 소진 시까지」, 없으면 null). 추출 이벤트의 applyNote를 안내에 함께 저장해요
   */
  public record PolicyInfo(
      String supportArea,
      String summary,
      String organization,
      String contact,
      String applyMethod,
      String applyUrl,
      String guideUrl,
      String amountText,
      String applyNote) {}

  /** 정책 ID → 출처 안내. 안내가 없는 정책은 빠져요. */
  public Map<UUID, PolicyInfo> policyInfos(java.util.Collection<UUID> rawPolicyIds) {
    Map<UUID, PolicyInfo> out = new java.util.HashMap<>();
    queries
        .policyInfos(rawPolicyIds)
        .forEach((id, info) -> out.put(id, json.readValue(info, PolicyInfo.class)));
    return out;
  }

  public List<NoticeForJudgment> findOpenForJudgment(NoticeField field) {
    List<NoticeForJudgment> notices =
        queries.openForJudgment(field.name()).stream()
            .map(
                row ->
                    new NoticeForJudgment(
                        row.id(),
                        false,
                        row.version(),
                        row.title(),
                        row.applyStart(),
                        row.applyEnd(),
                        regionCodes(row.nationwide(), row.regionCodes()),
                        row.frameJson() == null ? null : json.readTree(row.frameJson()),
                        row.detailUrl()))
            .toList();
    if (field != NoticeField.BUSINESS_SPACE) {
      return notices;
    }
    // 사업공간 모집(창업지원 API의 입주기업 모집)도 사업공간 판정 대상이에요. noticeId 자리에 정책 ID(rawPolicyId)를 주고 policy=true로
    // 표시해요(공고 상세 API로 찾을 수 없어 화면은 detailUrl 원문으로 보냄)
    List<NoticeForJudgment> spaces =
        queries.openPolicies("SPACE_RECRUITMENT", LocalDate.now(clock)).stream()
            .map(
                row ->
                    new NoticeForJudgment(
                        row.rawPolicyId(),
                        true,
                        row.version(),
                        row.title(),
                        row.applyStart(),
                        row.applyEnd(),
                        legalDongs(row.regionCodes()),
                        json.readTree(row.frameJson()),
                        row.detailUrl()))
            .toList();
    return java.util.stream.Stream.concat(notices.stream(), spaces.stream()).toList();
  }

  /**
   * 판정용 창업지원 사업 목록(판정 담당 요청): 마감되지 않은 창업지원 사업 전부를 한 번에 줘요. 판정 틀이 있는 사업만 저장되므로 frame은 늘 있어요.
   * programId는 수집 DB 정책 ID(rawPolicyId)예요. 마감일을 모르는데 상시 모집도 아닌 사업과 검수 대기(IN_REVIEW) 틀은 넣지 않아요
   * (openPolicies).
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
                    json.readTree(row.frameJson()),
                    row.detailUrl()))
        .toList();
  }

  /**
   * 판정에 쓰는 공고 내용 (eligibility가 불러요).
   *
   * @param judgment 판정 틀 (contracts/schemas/housing-judgment.v1.json). 아직 없으면 null
   * @param unitTypes 주택형별 보증금·월임대료 (준비할 돈 계산)
   * @param eligibilityComplete 추출이 자격 조건을 다 읽었는지 (quality.coverage.eligibility가
   *     EXTRACTED·NOT_IN_SOURCE· NOT_APPLICABLE). 일부만 읽었거나 실패했으면 false라서, 빠진 조건이 있을 수 있어요
   */
  public record JudgingSource(
      UUID noticeId,
      String field,
      String noticeType,
      JsonNode judgment,
      List<UnitPrice> unitTypes,
      boolean eligibilityComplete) {}

  public record UnitPrice(
      String houseType, java.math.BigDecimal exclusiveM2, Long depositWon, Long rentWon) {}

  /** 자격 조건을 다 읽었다고 볼 수 있는 추출 상태. */
  private static final java.util.Set<String> ELIGIBILITY_COMPLETE =
      java.util.Set.of("EXTRACTED", "NOT_IN_SOURCE", "NOT_APPLICABLE");

  /** 없거나 숨김·공개 전이면 404 NOTICE_NOT_FOUND. */
  public JudgingSource judgingSource(UUID noticeId) {
    CurrentRow current = current(noticeId);
    return judgingSource(
        noticeId, current.row().field(), current.row().noticeType(), payload(current));
  }

  /**
   * 판정에 쓰는 공고 내용 여러 개를 한 번에 (맞춤 목록). 현재 버전 번호만 먼저 묻고, 판정 틀은 공고 ID·버전으로 기억해 둔 것을 써요. 처음 보는 버전만
   * payload를 한 번에 읽어요. 숨김·공개 전·없는 공고는 빠져요.
   */
  public java.util.Map<UUID, JudgingSource> judgingSources(java.util.Collection<UUID> ids) {
    java.util.Map<UUID, JudgingSource> found = new java.util.HashMap<>();
    if (ids.isEmpty()) {
      return found;
    }
    java.util.Map<UUID, Integer> versions = queries.currentVersions(ids);
    List<UUID> missing = new java.util.ArrayList<>();
    versions.forEach(
        (id, version) -> {
          JudgingSource cached = judgingCache.get(new JudgingKey(id, version));
          if (cached == null) {
            missing.add(id);
          } else {
            found.put(id, cached);
          }
        });
    if (!missing.isEmpty()) {
      for (var row : queries.judgingRows(missing)) {
        JudgingSource source =
            judgingSource(row.id(), row.field(), row.noticeType(), json.readTree(row.payload()));
        judgingCache.put(new JudgingKey(row.id(), row.version()), source);
        found.put(row.id(), source);
      }
    }
    return found;
  }

  private static JudgingSource judgingSource(
      UUID noticeId, String field, String noticeType, JsonNode payload) {
    JsonNode judgment = payload.path("judgment");
    JsonNode notice = payload.path("notice");
    var units =
        NoticeDetailMapper.unitTypes(notice).stream()
            .map(u -> new UnitPrice(u.houseType(), u.exclusiveM2(), u.depositWon(), u.rentWon()))
            .toList();
    String coverage =
        notice.path("quality").path("coverage").path("eligibility").path("status").asString("");
    return new JudgingSource(
        noticeId,
        field,
        noticeType,
        judgment.isObject() ? judgment : null,
        units,
        ELIGIBILITY_COMPLETE.contains(coverage));
  }

  private record JudgingKey(UUID id, int version) {}

  /**
   * 판정 틀 기억 (공고 ID·버전 → 판정에 쓰는 내용). 공개 데이터만 담아요(사용자 조건·판정 결과는 담지 않아요). 새 버전이 오면 키가 달라져서 옛 값은 오래된
   * 순으로 밀려나요.
   */
  private final java.util.Map<JudgingKey, JudgingSource> judgingCache =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<JudgingKey, JudgingSource> e) {
              return size() > JUDGING_CACHE_SIZE;
            }
          });

  static final int JUDGING_CACHE_SIZE = 2000;

  /**
   * 공고 요약 여러 개를 한 번에 (알림 담당 요청: 공고 ID로 제목·마감일 등). 물은 ID → 요약이며 물은 순서를 지켜요. 다른 공고에 합쳐진 공고 ID는 상세
   * API처럼 합친(남은) 공고의 요약을 줘요(값의 noticeId가 물은 ID와 달라요). 숨김·공개 전·없는 공고는 빠져요. 마감된 공고도 줘요.
   */
  public java.util.Map<UUID, NoticeSummary> findSummaries(java.util.Collection<UUID> ids) {
    List<UUID> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
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

  /**
   * 마감일이 dates 중 하나이고 오늘 접수 중인 공고 (알림 담당 요청: 관심 공고 마감 D-3·D-1). 숨김·공개 전·합쳐진 공고·아직 시작 전인 공고는 빼요. 마감일
   * 순이에요.
   */
  public List<NoticeSummary> findOpenClosingOn(
      java.util.Collection<LocalDate> dates, LocalDate today) {
    return List.copyOf(findSummaries(queries.openClosingOn(dates, today)).values());
  }

  /**
   * 창업지원 사업·사업공간 모집 요약 하나 (알림 담당 요청: PolicyPublished·PolicyUpdated를 받았을 때, 관심 정책 저장). 검수 대기·없는 정책이면
   * 빈 값. 마감된 정책도 줘요.
   */
  public java.util.Optional<PolicySummary> findPolicySummary(UUID policyId) {
    LocalDate today = LocalDate.now(clock);
    return queries.policySummary(policyId).map(row -> policySummary(row, today));
  }

  /**
   * 마감일이 dates 중 하나이고 오늘 신청할 수 있는 창업지원 사업·사업공간 모집 (알림 담당 요청: 관심 정책 마감 D-3·D-1). 검수 대기·아직 시작 전인 정책은
   * 빼요. 마감일 순이에요.
   */
  public List<PolicySummary> findOpenPoliciesClosingOn(
      java.util.Collection<LocalDate> dates, LocalDate today) {
    return queries.openPoliciesClosingOn(dates, today).stream()
        .map(row -> policySummary(row, today))
        .toList();
  }

  private static PolicySummary policySummary(NoticeQueries.PolicySummaryRow row, LocalDate today) {
    return new PolicySummary(
        row.rawPolicyId(),
        row.version(),
        row.field(),
        row.title(),
        row.organization(),
        policyStatus(row, today),
        row.applyStart(),
        row.applyEnd(),
        row.regionCodes(),
        row.detailUrl());
  }

  /** 정책 신청 상태 (판정 목록 openPolicies와 같은 규칙: 마감일을 모르면 상시일 때만 열림). */
  private static String policyStatus(NoticeQueries.PolicySummaryRow row, LocalDate today) {
    if (row.applyEnd() != null && row.applyEnd().isBefore(today)) {
      return "CLOSED";
    }
    if (row.applyStart() != null && row.applyStart().isAfter(today)) {
      return "UPCOMING";
    }
    if (row.applyEnd() == null) {
      return row.applyAlways() ? "ALWAYS" : "UNKNOWN";
    }
    return "APPLYING";
  }

  /** 공고 요약 하나 (findSummaries와 같은 규칙). 숨김·공개 전·없는 공고면 빈 값. */
  public java.util.Optional<NoticeSummary> findSummary(UUID noticeId) {
    return java.util.Optional.ofNullable(findSummaries(List.of(noticeId)).get(noticeId));
  }

  /**
   * 공고에 가장 최근 붙이거나 바뀐 후속 안내 하나 (알림 담당 요청: NoticeUpdated(followUps)를 받았을 때 어떤 글인지). 그 이벤트를 받는 트랜잭션
   * 안에서 부르면 이벤트의 글이에요. 후속 안내가 없으면 빈 값. noticeId는 합친(남은) 공고 ID로 물으세요(findSummary의 noticeId).
   */
  public java.util.Optional<NoticeFollowUpSummary> findLatestFollowUp(UUID noticeId) {
    return followUps
        .findLatestByNotice(noticeId)
        .map(f -> new NoticeFollowUpSummary(f.rawPostId(), f.title(), f.postedOn()));
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

  CurrentRow current(UUID noticeId) {
    return queries
        .current(noticeId)
        .orElseThrow(() -> new CustomException(ErrorCode.NOTICE_NOT_FOUND));
  }

  JsonNode payload(CurrentRow current) {
    return json.readTree(current.payload());
  }

  /** 목록 줄들의 지역 이름을 한 번에 (줄마다 묻지 않게). */
  private Map<String, String> regionNames(List<ListRow> rows) {
    return regions.displayNames(rows.stream().map(r -> regionLookup(r.regionCode())).toList());
  }

  /** 목록 한 줄. 대표 위치 = area 좌표가 있는 첫 위치(notice 위치 ID 순), 생활권 점수 = 위치 중 가장 높은 점수(상세와 같은 기준). */
  private NoticeSummaryResponse summary(
      ListRow row, LocalDate today, List<NoticeSite> sites, Map<String, String> names) {
    NoticeSite main = sites.stream().filter(s -> s.lat() != null).findFirst().orElse(null);
    return new NoticeSummaryResponse(
        row.id(),
        row.field(),
        row.title(),
        row.provider(),
        row.noticeType(),
        row.regionCode(),
        names.get(regionLookup(row.regionCode())),
        row.status(),
        row.applyStart(),
        row.applyEnd(),
        NoticeStatus.dday(row.applyEnd(), today),
        row.postedOn(),
        row.needsCheck(),
        main == null ? null : main.lat(),
        main == null ? null : main.lng(),
        sites.size(),
        bestLivingScore(sites),
        main == null ? List.of() : main.tags());
  }

  /** 단지가 여럿이면 그중 가장 높은 생활권 점수. 점수가 하나도 없으면 null. */
  static Integer bestLivingScore(List<NoticeSite> sites) {
    return sites.stream()
        .map(NoticeSite::livingScore)
        .filter(Objects::nonNull)
        .max(Integer::compare)
        .orElse(null);
  }

  /** 시도 단위 코드(11000)는 시도 이름으로 찾아요. */
  static String regionLookup(String regionCode) {
    if (regionCode != null && regionCode.endsWith("000")) {
      return regionCode.substring(0, 2);
    }
    return regionCode;
  }

  /** 지도에 핀으로 줄 수 있는 공고 수 (넘으면 묶음). */
  static final int MAP_PINS = 300;

  /** 지도 범위에서 area에 묻는 좌표 수 (화면에 다 못 그리는 수라 넉넉히). */
  static final int MAP_POINTS = 5000;

  /**
   * 지도 (GET /notices/map). area가 지도 범위 안 공고 대표 좌표를 주고, 공고 정보·필터는 여기서 입혀요(숨김·공개 전은 빠지고 합쳐진 공고는 남은
   * 공고로 하나). 필터는 공개 목록과 같은 뜻이에요: statuses가 비면 마감을 뺀 전부, tenure는 공고 유형으로.
   */
  public NoticeMapResponse map(
      double swLat,
      double swLng,
      double neLat,
      double neLng,
      Integer zoom,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses) {
    return map(
        swLat, swLng, neLat, neLng, zoom, tenure, noticeTypes, providers, statuses, MAP_PINS);
  }

  /** 핀 한도를 정해서 (테스트에서 묶음을 보려고). */
  NoticeMapResponse map(
      double swLat,
      double swLng,
      double neLat,
      double neLng,
      Integer zoom,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses,
      int maxPins) {
    if (swLat >= neLat || swLng >= neLng) {
      throw new CustomException(ErrorCode.VALIDATION_FAILED, "지도 범위: 남서쪽(sw)이 북동쪽(ne)보다 작아야 해요");
    }
    var points = areas.pointsWithin(swLat, swLng, neLat, neLng, MAP_POINTS);
    var summaries =
        findSummaries(points.stream().map(NoticeAreaQueryService.NoticePoint::noticeId).toList());
    java.util.Map<UUID, NoticeMapResponse.Pin> pins = new java.util.LinkedHashMap<>();
    java.util.Map<UUID, String> regionOf = new java.util.HashMap<>();
    for (var point : points) {
      NoticeSummary s = summaries.get(point.noticeId());
      if (s == null
          || pins.containsKey(s.noticeId())
          || !mapShows(s, tenure, noticeTypes, providers, statuses)) {
        continue;
      }
      pins.put(
          s.noticeId(),
          new NoticeMapResponse.Pin(
              s.noticeId(), s.title(), s.noticeType(), s.applyEnd(), point.lat(), point.lng()));
      regionOf.put(
          s.noticeId(),
          s.regionCodes() == null || s.regionCodes().isEmpty() ? null : s.regionCodes().getFirst());
    }
    if (pins.size() <= maxPins) {
      return new NoticeMapResponse(List.of(), List.copyOf(pins.values()));
    }
    boolean bySido = zoom != null && zoom < 10;
    java.util.Map<String, List<NoticeMapResponse.Pin>> groups =
        new java.util.TreeMap<>(
            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
    pins.forEach(
        (id, pin) -> {
          String code = regionOf.get(id);
          String key =
              code == null ? null : code.substring(0, bySido ? 2 : Math.min(5, code.length()));
          groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(pin);
        });
    List<NoticeMapResponse.Cluster> clusters = new java.util.ArrayList<>();
    groups.forEach(
        (code, list) ->
            clusters.add(
                new NoticeMapResponse.Cluster(
                    code,
                    code == null
                        ? null
                        : regions.displayName(
                            regionLookup(code.length() == 2 ? code + "000" : code)),
                    list.size(),
                    list.stream().mapToDouble(NoticeMapResponse.Pin::lat).average().orElse(0),
                    list.stream().mapToDouble(NoticeMapResponse.Pin::lng).average().orElse(0))));
    return new NoticeMapResponse(clusters, List.of());
  }

  private static boolean mapShows(
      NoticeSummary s,
      String tenure,
      List<String> noticeTypes,
      List<String> providers,
      List<String> statuses) {
    boolean statusOk =
        statuses == null || statuses.isEmpty()
            ? !"CLOSED".equals(s.status())
            : statuses.contains(s.status());
    boolean tenureOk =
        tenure == null
            || ("BUY".equals(tenure) && NoticeListFilter.BUY_TYPES.contains(s.noticeType()))
            || ("RENT".equals(tenure) && !NoticeListFilter.BUY_TYPES.contains(s.noticeType()));
    return statusOk
        && tenureOk
        && (noticeTypes == null || noticeTypes.isEmpty() || noticeTypes.contains(s.noticeType()))
        && (providers == null || providers.isEmpty() || providers.contains(s.provider()));
  }
}
