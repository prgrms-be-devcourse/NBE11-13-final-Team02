package kr.mujuthaek.api.notification.service.internal;

import java.util.UUID;

/**
 * 알림 받을 회원 한 명 (notification.requested의 recipients[] 한 칸).
 *
 * @param email 이메일로 보낼 주소. 이메일을 켰고 이 종류를 켰고 인증된 주소일 때만, 아니면 null
 */
public record Recipient(UUID userId, String email, boolean quietEnabled) {}
