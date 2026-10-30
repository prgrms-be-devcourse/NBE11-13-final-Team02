package kr.mujuthaek.api.chat.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.mujuthaek.api.chat.domain.ReviewStatus;
import kr.mujuthaek.api.chat.dto.request.ReviewRequest;
import kr.mujuthaek.api.chat.dto.response.ChatReportResponse;
import kr.mujuthaek.api.chat.service.ChatAdminService;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 채팅 신고 검토 (관리자). /api/admin/** 은 시큐리티가 ADMIN만 들여보내요. */
@RestController
@RequestMapping("/api/admin/chat")
class ChatAdminController {

  private final ChatAdminService admin;

  ChatAdminController(ChatAdminService admin) {
    this.admin = admin;
  }

  /** 신고 목록 (최신순, 기본 검토 전). */
  @GetMapping("/reports")
  CursorPageResponse<ChatReportResponse> reports(
      @RequestParam(defaultValue = "PENDING") ReviewStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return admin.reports(status, cursor, size);
  }

  /** 검토 전 신고가 걸린 글 수 (사이드바 숫자). */
  @GetMapping("/reports/counts")
  ChatReportResponse.Counts counts() {
    return admin.counts();
  }

  /** 가림 유지(KEEP_HIDDEN) 또는 다시 보이기(RESTORE). */
  @PatchMapping("/messages/{id}")
  ResponseEntity<Void> review(
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest request,
      @AuthenticationPrincipal LoginUser user) {
    admin.review(id, request.action(), user.userId());
    return ResponseEntity.noContent().build();
  }
}
