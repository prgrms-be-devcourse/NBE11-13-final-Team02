package kr.mujuthaek.api.benefit.event;

import kr.mujuthaek.api.benefit.service.internal.BenefitStatusService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 혜택 상태(접수 예정 → 접수 중 → 마감)를 매일 한국 시간 0시 5분에 다시 계산해요. benefit.status-refresh.enabled=false면 꺼요. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "benefit.status-refresh.enabled", matchIfMissing = true)
class BenefitStatusScheduler {

  private final BenefitStatusService status;

  BenefitStatusScheduler(BenefitStatusService status) {
    this.status = status;
  }

  @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
  void refresh() {
    status.refresh();
  }
}
