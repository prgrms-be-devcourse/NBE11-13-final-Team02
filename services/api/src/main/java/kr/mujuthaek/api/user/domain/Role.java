package kr.mujuthaek.api.user.domain;

/** 회원 권한. 처음 로그인하면 USER, ADMIN은 DB에서 직접 바꿔요 (README 참고). */
public enum Role {
  USER,
  ADMIN;

  /** 스프링 시큐리티 권한 이름 (hasRole("ADMIN")이 ROLE_ADMIN을 봐요). */
  public String authority() {
    return "ROLE_" + name();
  }
}
