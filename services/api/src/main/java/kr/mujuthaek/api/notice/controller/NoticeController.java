package kr.mujuthaek.api.notice.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.notice.dto.request.NoticeListFilter;
import kr.mujuthaek.api.notice.dto.response.NoticeDetailResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeSummaryResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeUnitResponse;
import kr.mujuthaek.api.notice.dto.response.NoticeVersionResponse;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 누구나 보는 공고 API (로그인 없이). 판정이 섞인 맞춤 목록·판정은 POST로 따로 있어요. */
@RestController
@RequestMapping("/api/v1/notices")
class NoticeController {

  private final NoticeQueryService notices;

  NoticeController(NoticeQueryService notices) {
    this.notices = notices;
  }

  /**
   * 공개 목록. 예: GET /api/v1/notices?regionCodes=11440,41&tenure=RENT&statuses=APPLYING&sort=DEADLINE
   * &applyEndFrom=2026-11-01&applyEndTo=2026-11-30
   */
  @GetMapping
  CursorPageResponse<NoticeSummaryResponse> list(
      @RequestParam(required = false) String field,
      @RequestParam(required = false) List<String> regionCodes,
      @RequestParam(required = false) String tenure,
      @RequestParam(required = false) List<String> noticeTypes,
      @RequestParam(required = false) List<String> providers,
      @RequestParam(required = false) List<String> statuses,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate applyEndFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate applyEndTo,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    var filter =
        new NoticeListFilter(
            field,
            regionCodes,
            tenure,
            noticeTypes,
            providers,
            statuses,
            sort,
            applyEndFrom,
            applyEndTo);
    return notices.list(filter, cursor, size);
  }

  /** 상세 (요약, 일정, 주택형별 조건, 단지, 자격 조건과 근거, 첨부 링크). */
  @GetMapping("/{id}")
  NoticeDetailResponse detail(@PathVariable UUID id) {
    return notices.detail(id);
  }

  /** 주택목록 (호별 주소·면적·금액). 주택목록이 없으면 빈 목록. */
  @GetMapping("/{id}/units")
  CursorPageResponse<NoticeUnitResponse> units(
      @PathVariable UUID id,
      @RequestParam(required = false) String siteId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return notices.units(id, siteId, cursor, size);
  }

  /** 정정 이력 (버전, 바뀐 항목, 시각). */
  @GetMapping("/{id}/versions")
  List<NoticeVersionResponse> versions(@PathVariable UUID id) {
    return notices.versions(id);
  }
}
