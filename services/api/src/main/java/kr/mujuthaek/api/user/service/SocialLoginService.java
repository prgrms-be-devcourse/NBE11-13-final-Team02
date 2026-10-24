package kr.mujuthaek.api.user.service;

import java.time.Clock;
import java.time.Instant;
import kr.mujuthaek.api.user.domain.SocialAccount;
import kr.mujuthaek.api.user.domain.User;
import kr.mujuthaek.api.user.repository.SocialAccountRepository;
import kr.mujuthaek.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소셜 로그인 처리: 처음이면 가입(USER), 이미 있으면 닉네임·마지막 로그인 시각만 갱신해요. */
@Service
public class SocialLoginService {

  private final UserRepository users;
  private final SocialAccountRepository socialAccounts;
  private final Clock clock;

  @Autowired
  public SocialLoginService(UserRepository users, SocialAccountRepository socialAccounts) {
    this(users, socialAccounts, Clock.systemUTC());
  }

  SocialLoginService(UserRepository users, SocialAccountRepository socialAccounts, Clock clock) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.clock = clock;
  }

  @Transactional
  public LoginUser login(SocialProfile profile) {
    Instant now = clock.instant();
    User user =
        socialAccounts
            .findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
            .flatMap(account -> users.findById(account.getUserId()))
            .map(
                existing -> {
                  existing.loggedIn(profile.nickname(), now);
                  return existing;
                })
            .orElseGet(() -> join(profile, now));
    return new LoginUser(user.getId(), user.getNickname(), user.getRole().name());
  }

  private User join(SocialProfile profile, Instant now) {
    User user = users.save(User.join(profile.nickname(), now));
    socialAccounts.save(
        new SocialAccount(user.getId(), profile.provider(), profile.providerUserId(), now));
    return user;
  }
}
