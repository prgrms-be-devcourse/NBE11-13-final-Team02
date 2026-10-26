package kr.mujuthaek.api.user.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 회원 관리의 회원 한 명. 소셜 계정 원본 정보(이메일 등)는 주지 않아요.
 *
 * @param role USER 또는 ADMIN
 * @param providers 이어진 소셜 로그인 (KAKAO, NAVER, GOOGLE)
 */
public record AdminUserResponse(
    UUID id,
    String nickname,
    String role,
    List<String> providers,
    OffsetDateTime joinedAt,
    OffsetDateTime lastLoginAt) {

  /** 회원 목록 한 쪽. total은 조건에 맞는 전체 수 */
  public record Page(List<AdminUserResponse> items, long total) {}

  /**
   * 권한 변경 기록 한 줄.
   *
   * @param changedBy 바꾼 관리자 (탈퇴했으면 null)
   */
  public record RoleChange(
      java.time.Instant changedAt,
      UUID userId,
      String nickname,
      String fromRole,
      String toRole,
      UUID changedBy,
      String changedByNickname) {}
}
