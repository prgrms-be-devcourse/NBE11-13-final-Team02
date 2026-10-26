package kr.mujuthaek.api.area.event;

import kr.mujuthaek.api.area.service.AreaReportService;
import kr.mujuthaek.api.common.event.UserWithdrawn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 회원이 탈퇴하면(커밋 뒤) 그 회원의 생활권 제보를 지워요 (요구사항 3-16). */
@Component
class AreaReportWithdrawalListener {

  private final AreaReportService reports;

  AreaReportWithdrawalListener(AreaReportService reports) {
    this.reports = reports;
  }

  @TransactionalEventListener(fallbackExecution = true)
  void on(UserWithdrawn event) {
    reports.forget(event.userId());
  }
}
