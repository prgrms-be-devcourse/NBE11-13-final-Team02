package kr.mujuthaek.api.user.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 관리자 화면의 권한 바꾸기: USER 또는 ADMIN. */
public record RoleChangeRequest(@NotNull @Pattern(regexp = "USER|ADMIN") String role) {}
