package kr.mujuthaek.api.user.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.user.dto.response.AdminUserResponse;
import kr.mujuthaek.api.user.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리: 회원 찾기, 관리자 지정·해제, 권한 변경 기록.
 *
 * <p>자기 권한은 바꿀 수 없고(실수로 관리자 화면에서 빠지지 않게), 마지막 관리자는 해제할 수 없어요. 관리자를 해제하면 그 회원의 로그인을 바로 끊어요(access
 * token은 짧게 남지만 다시 받을 수 없어요). 지정은 그 회원이 다음에 토큰을 다시 받을 때 반영돼요.
 */
@Service
public class AdminUserService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
  private static final int PAGE_SIZE = 30;

  private final AdminUserRepository users;
  private final Clock clock;

  public AdminUserService(AdminUserRepository users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  /** 회원 찾기 (한 쪽 30명). */
  @Transactional(readOnly = true)
  public AdminUserResponse.Page search(String query, String role, int page) {
    String q = query == null || query.isBlank() ? null : query.strip();
    String r = role == null || role.isBlank() ? null : role;
    int p = Math.max(0, page);
    return new AdminUserResponse.Page(
        users.search(q, r, PAGE_SIZE, p * PAGE_SIZE).stream()
            .map(AdminUserService::response)
            .toList(),
        users.count(q, r));
  }

  /** 여러 회원 (처리 기록에서 관리자 이름 찾기). 최대 100명. */
  @Transactional(readOnly = true)
  public List<AdminUserResponse> findAll(List<UUID> ids) {
    return users.findAll(ids.stream().distinct().limit(100).toList()).stream()
        .map(AdminUserService::response)
        .toList();
  }

  /** 관리자 지정·해제. */
  @Transactional
  public AdminUserResponse changeRole(UUID userId, String role, UUID admin) {
    if (userId.equals(admin)) {
      throw new CustomException(ErrorCode.CONFLICT, "내 권한은 바꿀 수 없어요. 다른 관리자에게 부탁해 주세요");
    }
    var target = users.find(userId).orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    if ("USER".equals(role) && "ADMIN".equals(target.role()) && users.adminCount() <= 1) {
      throw new CustomException(ErrorCode.CONFLICT, "관리자가 한 명뿐이라 해제할 수 없어요");
    }
    if (users.changeRole(userId, role, admin, clock.instant())) {
      if ("USER".equals(role)) {
        users.revokeLogins(userId);
      }
      log.info("관리자 권한 변경: user={} role={} by={}", userId, role, admin);
    }
    return response(users.find(userId).orElseThrow());
  }

  /** 최근 days일 권한 변경 기록 (최대 200줄). */
  @Transactional(readOnly = true)
  public List<AdminUserResponse.RoleChange> roleChanges(int days) {
    Instant since = clock.instant().minus(Duration.ofDays(Math.clamp(days, 1, 90)));
    return users.roleChanges(since, 200).stream()
        .map(
            c ->
                new AdminUserResponse.RoleChange(
                    c.changedAt(),
                    c.userId(),
                    c.nickname(),
                    c.fromRole(),
                    c.toRole(),
                    c.changedBy(),
                    c.changedByNickname()))
        .toList();
  }

  private static AdminUserResponse response(AdminUserRepository.Row r) {
    return new AdminUserResponse(
        r.id(), r.nickname(), r.role(), r.providers(), r.joinedAt(), r.lastLoginAt());
  }
}
