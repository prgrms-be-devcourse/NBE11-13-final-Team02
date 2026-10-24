package kr.mujuthaek.api.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루 한 번 만료된 refresh token과 끝내지 않은 탈퇴 티켓을 지워요. 로그인마다 refresh token이 한 줄씩 생기고, 바꾸지 않고 만료된 줄은 이것 말고는
 * 지워지지 않아요.
 */
@Component
class ExpiredTokenCleanup {

  private static final Logger log = LoggerFactory.getLogger(ExpiredTokenCleanup.class);

  private final AuthTokenService tokens;
  private final WithdrawalService withdrawals;

  ExpiredTokenCleanup(AuthTokenService tokens, WithdrawalService withdrawals) {
    this.tokens = tokens;
    this.withdrawals = withdrawals;
  }

  @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
  void run() {
    int refreshTokens = tokens.deleteExpired();
    int tickets = withdrawals.deleteExpiredTickets();
    log.info("만료된 로그인 정보 정리: refresh token {}줄, 탈퇴 티켓 {}줄", refreshTokens, tickets);
  }
}
