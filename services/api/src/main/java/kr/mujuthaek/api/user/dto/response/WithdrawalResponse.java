package kr.mujuthaek.api.user.dto.response;

/**
 * 탈퇴 시작 응답. 프론트는 {@code reauthUrl}로 페이지를 이동시켜요 (fetch가 아니라 주소 이동).
 *
 * @param provider 다시 로그인할 소셜 (KAKAO, NAVER, GOOGLE)
 * @param reauthUrl 재인증 주소 ({@code /oauth2/authorization/{제공자}?withdraw=일회용 티켓})
 * @param expiresIn 티켓 유효 시간 (초)
 */
public record WithdrawalResponse(String provider, String reauthUrl, long expiresIn) {}
