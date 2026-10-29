package kr.mujuthaek.api.notice.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import kr.mujuthaek.api.notice.dto.request.GeneralReportRequest;
import kr.mujuthaek.api.notice.dto.request.NoticeReportRequest;
import kr.mujuthaek.api.notice.dto.response.NoticeReportCreatedResponse;
import kr.mujuthaek.api.notice.service.internal.NoticeReportService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공고 제보 받기 (로그인한 회원만). 예: POST /api/v1/notices/{id}/reports {"field":"보증금","currentValue":"2,400만
 * 원","reportedValue":"2,600만 원","note":"공고문 4쪽"}
 *
 * <p>제보한 회원은 로그인 계정 ID(Principal 이름)로만 남겨요. 사용자 모듈 타입에 기대지 않아요.
 */
@RestController
class NoticeReportController {

  private final NoticeReportService reports;

  NoticeReportController(NoticeReportService reports) {
    this.reports = reports;
  }

  /** 창업지원 사업 제보 (로그인한 회원만). id는 판정 API의 programId(rawPolicyId)와 같아요. */
  @PostMapping("/api/v1/programs/{id}/reports")
  NoticeReportCreatedResponse reportProgram(
      @PathVariable UUID id, @Valid @RequestBody NoticeReportRequest request, Principal principal) {
    return reports.reportProgram(id, request, principal == null ? null : principal.getName());
  }

  /** 주거·복지 정책(혜택) 제보 (로그인한 회원만). id는 정책 원본 ID(policy.extracted의 rawPolicyId)예요. */
  @PostMapping("/api/v1/benefits/{id}/reports")
  NoticeReportCreatedResponse reportBenefit(
      @PathVariable UUID id, @Valid @RequestBody NoticeReportRequest request, Principal principal) {
    return reports.reportBenefit(id, request, principal == null ? null : principal.getName());
  }

  /** 일반 제보 (로그인한 회원만): 공고·혜택·사업에 묶이지 않은 제보. field는 MISSING_NOTICE · WRONG_INFO · APP_ERROR · ETC */
  @PostMapping("/api/v1/reports")
  NoticeReportCreatedResponse reportGeneral(
      @Valid @RequestBody GeneralReportRequest request, Principal principal) {
    return reports.reportGeneral(request, principal == null ? null : principal.getName());
  }

  @PostMapping("/api/v1/notices/{id}/reports")
  NoticeReportCreatedResponse report(
      @PathVariable UUID id, @Valid @RequestBody NoticeReportRequest request, Principal principal) {
    return reports.report(id, request, principal == null ? null : principal.getName());
  }
}
