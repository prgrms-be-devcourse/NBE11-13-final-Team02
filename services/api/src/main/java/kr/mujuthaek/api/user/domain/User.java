package kr.mujuthaek.api.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** 회원. 소득·자산·나이는 저장하지 않아요 (판정할 때만 받아요). */
@Entity
@Table(schema = "account", name = "users")
public class User {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(length = 50)
  private String nickname;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  private Instant lastLoginAt;

  protected User() {}

  private User(String nickname, Instant now) {
    this.nickname = nickname;
    this.role = Role.USER;
    this.createdAt = now;
    this.updatedAt = now;
    this.lastLoginAt = now;
  }

  /** 처음 소셜 로그인한 회원. 권한은 USER로 시작해요. */
  public static User join(String nickname, Instant now) {
    return new User(trim(nickname), now);
  }

  /** 로그인할 때마다: 닉네임은 소셜 계정에서 바뀌었으면 따라가고, 마지막 로그인 시각을 남겨요. */
  public void loggedIn(String nickname, Instant now) {
    if (nickname != null && !nickname.isBlank()) {
      this.nickname = trim(nickname);
    }
    this.lastLoginAt = now;
    this.updatedAt = now;
  }

  private static String trim(String nickname) {
    if (nickname == null) {
      return null;
    }
    String value = nickname.strip();
    return value.length() > 50 ? value.substring(0, 50) : value;
  }

  public UUID getId() {
    return id;
  }

  public String getNickname() {
    return nickname;
  }

  public Role getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }
}
