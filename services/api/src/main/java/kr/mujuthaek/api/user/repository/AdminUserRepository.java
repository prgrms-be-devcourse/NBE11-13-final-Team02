package kr.mujuthaek.api.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 관리자 회원 관리: 찾기·권한 바꾸기·권한 변경 기록 (account 스키마). */
@Repository
public class AdminUserRepository {

  private static final String COLUMNS =
      "u.id, u.nickname, u.role, u.created_at, u.last_login_at, coalesce((select string_agg(s.provider,"
          + " ',' order by s.provider) from account.social_accounts s where s.user_id = u.id), '')"
          + " as providers";

  /** 회원 한 명. providers는 이어진 소셜 로그인(KAKAO·NAVER·GOOGLE) */
  public record Row(
      UUID id,
      String nickname,
      String role,
      OffsetDateTime joinedAt,
      OffsetDateTime lastLoginAt,
      List<String> providers) {}

  public record RoleChange(
      Instant changedAt,
      UUID userId,
      String nickname,
      String fromRole,
      String toRole,
      UUID changedBy,
      String changedByNickname) {}

  private final JdbcClient jdbc;

  public AdminUserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
    String providers = rs.getString("providers");
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("nickname"),
        rs.getString("role"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("last_login_at", OffsetDateTime.class),
        providers.isEmpty() ? List.of() : Arrays.asList(providers.split(",")));
  }

  /**
   * 회원 찾기. query는 닉네임 일부 또는 회원 ID 전체, role은 USER·ADMIN(없으면 전체). 가입한 지 최근 순.
   *
   * @param offset 건너뛸 수 (페이지)
   */
  public List<Row> search(String query, String role, int limit, int offset) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from account.users u where "
                + where()
                + " order by u.created_at desc, u.id limit :limit offset :offset")
        .param("q", query)
        .param("like", query == null ? null : "%" + escape(query) + "%")
        .param("role", role)
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, n) -> row(rs))
        .list();
  }

  public long count(String query, String role) {
    return jdbc.sql("select count(*) from account.users u where " + where())
        .param("q", query)
        .param("like", query == null ? null : "%" + escape(query) + "%")
        .param("role", role)
        .query(Long.class)
        .single();
  }

  private static String where() {
    return "(cast(:q as text) is null or u.nickname ilike :like escape '\\' or u.id::text = :q)"
        + " and (cast(:role as text) is null or u.role = :role)";
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 여러 회원 (처리 기록의 관리자 이름 찾기 등). 없는 ID는 빠져요. */
  public List<Row> findAll(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("select " + COLUMNS + " from account.users u where u.id in (:ids)")
        .param("ids", ids)
        .query((rs, n) -> row(rs))
        .list();
  }

  public Optional<Row> find(UUID id) {
    return findAll(List.of(id)).stream().findFirst();
  }

  public long adminCount() {
    return jdbc.sql("select count(*) from account.users where role = 'ADMIN'")
        .query(Long.class)
        .single();
  }

  /** 권한을 바꾸고 기록을 남겨요. 이미 그 권한이면 아무것도 하지 않고 false. */
  public boolean changeRole(UUID userId, String toRole, UUID changedBy, Instant now) {
    String from =
        jdbc.sql("select role from account.users where id = :id for update")
            .param("id", userId)
            .query(String.class)
            .optional()
            .orElse(null);
    if (from == null || from.equals(toRole)) {
      return false;
    }
    jdbc.sql("update account.users set role = :role, updated_at = :now where id = :id")
        .param("role", toRole)
        .param("now", Timestamp.from(now))
        .param("id", userId)
        .update();
    jdbc.sql(
            "insert into account.role_changes (user_id, from_role, to_role, changed_by, changed_at)"
                + " values (:user, :from, :to, :by, :now)")
        .param("user", userId)
        .param("from", from)
        .param("to", toRole)
        .param("by", changedBy)
        .param("now", Timestamp.from(now))
        .update();
    return true;
  }

  /** 그 회원의 로그인(refresh token)을 모두 끊어요. 관리자 권한을 뺄 때 바로 반영하려고. */
  public int revokeLogins(UUID userId) {
    return jdbc.sql("delete from account.refresh_tokens where user_id = :id")
        .param("id", userId)
        .update();
  }

  /** 최근 권한 변경 기록. */
  public List<RoleChange> roleChanges(Instant since, int limit) {
    return jdbc.sql(
            "select c.changed_at, c.user_id, u.nickname, c.from_role, c.to_role, c.changed_by,"
                + " b.nickname as by_nickname from account.role_changes c"
                + " join account.users u on u.id = c.user_id"
                + " left join account.users b on b.id = c.changed_by"
                + " where c.changed_at >= :since order by c.changed_at desc, c.id desc limit :limit")
        .param("since", Timestamp.from(since))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new RoleChange(
                    rs.getTimestamp("changed_at").toInstant(),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("nickname"),
                    rs.getString("from_role"),
                    rs.getString("to_role"),
                    rs.getObject("changed_by", UUID.class),
                    rs.getString("by_nickname")))
        .list();
  }
}
