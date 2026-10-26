package kr.mujuthaek.api.user.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.user.dto.request.RoleChangeRequest;
import kr.mujuthaek.api.user.dto.response.AdminUserResponse;
import kr.mujuthaek.api.user.service.AdminUserService;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 (ADMIN만, SecurityConfig의 /api/admin/**).
 *
 * <pre>
 * GET   /api/admin/users?query=&role=&page=0   회원 찾기 (닉네임 일부·회원 ID, 권한 USER·ADMIN, 한 쪽 30명)
 * GET   /api/admin/users?ids=a,b               여러 회원 (처리 기록의 관리자 이름 등, 최대 100명)
 * PATCH /api/admin/users/{id}/role             관리자 지정·해제 {"role": "ADMIN" | "USER"}
 * GET   /api/admin/users/role-changes?days=30  권한 변경 기록
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController {

  private final AdminUserService users;

  AdminUserController(AdminUserService users) {
    this.users = users;
  }

  @GetMapping
  Object list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String role,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) List<UUID> ids) {
    if (ids != null && !ids.isEmpty()) {
      return users.findAll(ids);
    }
    return users.search(query, role, page);
  }

  @PatchMapping("/{id}/role")
  AdminUserResponse changeRole(
      @PathVariable UUID id,
      @Valid @RequestBody RoleChangeRequest request,
      @AuthenticationPrincipal LoginUser admin) {
    return users.changeRole(id, request.role(), admin.userId());
  }

  @GetMapping("/role-changes")
  List<AdminUserResponse.RoleChange> roleChanges(@RequestParam(defaultValue = "30") int days) {
    return users.roleChanges(days);
  }
}
