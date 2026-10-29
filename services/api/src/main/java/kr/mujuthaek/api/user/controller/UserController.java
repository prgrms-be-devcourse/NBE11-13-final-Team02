package kr.mujuthaek.api.user.controller;

import jakarta.validation.Valid;
import java.util.Locale;
import kr.mujuthaek.api.user.dto.request.ContactEmailRequest;
import kr.mujuthaek.api.user.dto.response.MeResponse;
import kr.mujuthaek.api.user.dto.response.MyReportsResponse;
import kr.mujuthaek.api.user.dto.response.WithdrawalResponse;
import kr.mujuthaek.api.user.service.ContactEmailService;
import kr.mujuthaek.api.user.service.LoginUser;
import kr.mujuthaek.api.user.service.MyReportService;
import kr.mujuthaek.api.user.service.UserService;
import kr.mujuthaek.api.user.service.WithdrawalService;
import kr.mujuthaek.api.user.service.WithdrawalStart;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/users")
class UserController {

  private final UserService userService;
  private final WithdrawalService withdrawalService;
  private final ContactEmailService contactEmails;
  private final MyReportService myReports;

  UserController(
      UserService userService,
      WithdrawalService withdrawalService,
      ContactEmailService contactEmails,
      MyReportService myReports) {
    this.userService = userService;
    this.withdrawalService = withdrawalService;
    this.contactEmails = contactEmails;
    this.myReports = myReports;
  }

  /**
   * 탈퇴 시작. 바로 지우지 않고, 본인 확인과 소셜 연결 끊기를 위해 그 소셜로 다시 로그인하는 주소를 줘요. 프론트가 그 주소로 이동하면 다시 로그인 → 회원
   * 삭제(hard delete) → 소셜 연결 끊기 → {@code FRONTEND_URL/withdrawn}으로 돌아가요.
   *
   * @param provider 여러 소셜을 연결한 경우 고를 소셜 (kakao, naver, google). 비우면 처음 연결한 소셜
   */
  @PostMapping("/me/withdrawal")
  WithdrawalResponse startWithdrawal(
      @AuthenticationPrincipal LoginUser user, @RequestParam(required = false) String provider) {
    WithdrawalStart start = withdrawalService.start(user.userId(), provider);
    String reauthUrl =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/oauth2/authorization/{provider}")
            .queryParam("withdraw", start.ticket())
            .buildAndExpand(start.provider().toLowerCase(Locale.ROOT))
            .toUriString();
    return new WithdrawalResponse(
        start.provider(), reauthUrl, start.ttl().toSeconds(), start.otherProviders());
  }

  /** 내 정보. 로그인하지 않았으면 401이에요 (프론트는 이걸로 로그인 여부를 확인해요). */
  @GetMapping("/me")
  MeResponse me(@AuthenticationPrincipal LoginUser user) {
    return userService.getMe(user.userId());
  }

  /** 내 제보 (공고·혜택·창업지원 사업·생활권·일반 제보, 최근 100건) — 처리 상태·결과·답 */
  @GetMapping("/me/reports")
  MyReportsResponse myReports(@AuthenticationPrincipal LoginUser user) {
    return new MyReportsResponse(myReports.mine(user.userId()));
  }

  /** 연락용 이메일 등록·변경 (선택, 가입 마무리 화면·마이페이지). 주소는 {@code GET /me}의 email로 확인해요 */
  @PutMapping("/me/email")
  ResponseEntity<Void> changeEmail(
      @AuthenticationPrincipal LoginUser user, @Valid @RequestBody ContactEmailRequest request) {
    contactEmails.change(user.userId(), request.email(), request.consentVersion());
    return ResponseEntity.noContent().build();
  }

  /** 연락용 이메일 삭제 (동의 철회). 없어도 204 */
  @DeleteMapping("/me/email")
  ResponseEntity<Void> removeEmail(@AuthenticationPrincipal LoginUser user) {
    contactEmails.remove(user.userId());
    return ResponseEntity.noContent().build();
  }
}
