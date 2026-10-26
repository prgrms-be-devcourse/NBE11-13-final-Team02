package kr.mujuthaek.api.user.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.user.dto.request.AdminRequests;
import kr.mujuthaek.api.user.dto.request.RoleChangeRequest;
import kr.mujuthaek.api.user.dto.response.AdminMeResponse;
import kr.mujuthaek.api.user.dto.response.AdminUserResponse;
import kr.mujuthaek.api.user.service.AdminUserService;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리. 경로마다 필요한 권한은 SecurityConfig(AdminAuthorization)가 먼저 보고, 주고 빼는 규칙은 AdminUserService가
 * 봐요.
 *
 * <pre>
 * GET    /api/admin/me                           내 관리자 권한 (관리자 누구나)
 * GET    /api/admin/users?query=&filter=&page=0  회원 찾기 (filter: USER·ADMIN·SUSPENDED, 한 쪽 30명)
 * GET    /api/admin/users/names?ids=a,b          이름만 (관리자 누구나, 최대 100명)
 * PATCH  /api/admin/users/{id}/role              관리자 지정·해제 {"role": "ADMIN" | "USER"}     「관리자 지정」
 * PUT    /api/admin/users/{id}/permissions       권한 맞추기 {"permissions": [...]}            「권한 부여」
 * PUT    /api/admin/users/{id}/suspension        이용 정지 {"reason", "until"?}                「이용 정지」
 * DELETE /api/admin/users/{id}/suspension        이용 정지 해제                                 「이용 정지」
 * GET    /api/admin/users/changes?days=30        관리자 변경 기록
 * POST   /api/admin/super/transfer               최상위 넘기기 {"userId"}                     최상위만
 * PUT    /api/admin/super/freeze                 관리자 긴급 정지 {"reason"?}                  최상위만
 * DELETE /api/admin/super/freeze                 긴급 정지 풀기                                최상위만
 * </pre>
 */
@RestController
@RequestMapping("/api/admin")
class AdminUserController {

  private final AdminUserService users;

  AdminUserController(AdminUserService users) {
    this.users = users;
  }

  @GetMapping("/me")
  AdminMeResponse me(@AuthenticationPrincipal LoginUser admin) {
    return users.me(admin.userId());
  }

  @GetMapping("/users")
  AdminUserResponse.Page list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String filter,
      @RequestParam(defaultValue = "0") int page) {
    return users.search(query, filter, page);
  }

  @GetMapping("/users/names")
  List<AdminUserResponse.Name> names(@RequestParam List<UUID> ids) {
    return users.names(ids);
  }

  @PatchMapping("/users/{id}/role")
  AdminUserResponse changeRole(
      @PathVariable UUID id,
      @Valid @RequestBody RoleChangeRequest request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.changeRole(id, request.role(), admin.userId());
  }

  @PutMapping("/users/{id}/permissions")
  AdminUserResponse permissions(
      @PathVariable UUID id,
      @Valid @RequestBody AdminRequests.Permissions request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.setPermissions(id, request.permissions(), admin.userId());
  }

  @PutMapping("/users/{id}/suspension")
  AdminUserResponse suspend(
      @PathVariable UUID id,
      @Valid @RequestBody AdminRequests.Suspend request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.suspend(id, request.reason(), request.until(), admin.userId());
  }

  @DeleteMapping("/users/{id}/suspension")
  AdminUserResponse unsuspend(@PathVariable UUID id, @AuthenticationPrincipal LoginUser admin) {
    return users.unsuspend(id, admin.userId());
  }

  @GetMapping("/users/changes")
  List<AdminUserResponse.Change> changes(@RequestParam(defaultValue = "30") int days) {
    return users.changes(days);
  }

  @PostMapping("/super/transfer")
  AdminUserResponse transfer(
      @Valid @RequestBody AdminRequests.Transfer request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.transfer(request.userId(), admin.userId());
  }

  @PutMapping("/super/freeze")
  AdminMeResponse freeze(
      @Valid @RequestBody(required = false) AdminRequests.Freeze request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.freeze(request == null ? null : request.reason(), admin.userId());
  }

  @DeleteMapping("/super/freeze")
  AdminMeResponse unfreeze(@AuthenticationPrincipal LoginUser admin) {
    return users.unfreeze(admin.userId());
  }
}
