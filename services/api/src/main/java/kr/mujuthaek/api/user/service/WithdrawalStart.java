package kr.mujuthaek.api.user.service;

import java.time.Duration;

/**
 * 탈퇴 시작 결과.
 *
 * @param provider 다시 로그인할 소셜 (KAKAO, NAVER, GOOGLE)
 * @param ticket 일회용 티켓 원문. 재인증 주소의 withdraw 파라미터로만 써요
 * @param ttl 티켓 유효 시간
 */
public record WithdrawalStart(String provider, String ticket, Duration ttl) {}
