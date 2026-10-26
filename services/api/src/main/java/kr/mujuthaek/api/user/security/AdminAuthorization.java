package kr.mujuthaek.api.user.security;

import java.util.List;
import java.util.function.Predicate;
import kr.mujuthaek.api.user.domain.AdminPermission;
import kr.mujuthaek.api.user.service.AdminAccess;
import kr.mujuthaek.api.user.service.AdminUserService;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * 관리자 화면 경로의 권한 확인. 토큰에 적힌 권한이 아니라 요청마다 DB의 지금 상태(단계·받은 권한·이용 정지·긴급 정지)를 봐요. 그래서 관리자를 해제하거나 권한을 빼면
 * 남은 access token으로도 바로 막혀요. 한 요청 안에서는 한 번만 읽어요.
 */
@Component
class AdminAuthorization {

  private static final String ATTRIBUTE = AdminAuthorization.class.getName();

  private final AdminUserService admins;

  AdminAuthorization(AdminUserService admins) {
    this.admins = admins;
  }

  /** 관리자 단계이기만 하면 (정지 중이어도 내 상태는 볼 수 있게: /api/admin/me). */
  AuthorizationManager<RequestAuthorizationContext> admin() {
    return check(a -> a.role().isAdmin());
  }

  /** 지금 관리자 화면을 쓸 수 있는 관리자 누구나. */
  AuthorizationManager<RequestAuthorizationContext> active() {
    return check(AdminAccess::active);
  }

  /** 이 권한 중 하나라도 있으면. */
  AuthorizationManager<RequestAuthorizationContext> anyOf(AdminPermission... permissions) {
    return check(a -> List.of(permissions).stream().anyMatch(a::has));
  }

  AuthorizationManager<RequestAuthorizationContext> superOnly() {
    return check(a -> a.isSuper() && a.active());
  }

  private AuthorizationManager<RequestAuthorizationContext> check(Predicate<AdminAccess> rule) {
    return (authentication, context) -> {
      var auth = authentication.get();
      if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
        return new AuthorizationDecision(false);
      }
      var request = context.getRequest();
      AdminAccess access;
      if (request.getAttribute(ATTRIBUTE) instanceof AdminAccess cached) {
        access = cached;
      } else {
        access = admins.access(user.userId());
        request.setAttribute(ATTRIBUTE, access);
      }
      return new AuthorizationDecision(rule.test(access));
    };
  }
}
