package kr.mujuthaek.api.notice.event;

import kr.mujuthaek.api.common.event.UserWithdrawn;
import kr.mujuthaek.api.notice.service.internal.NoticeReportService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 회원이 탈퇴하면(커밋 뒤) 그 회원의 공고 제보를 지워요 (요구사항 3-16: 탈퇴 후 데이터 0건). */
@Component
class UserWithdrawnListener {

  private final NoticeReportService reports;

  UserWithdrawnListener(NoticeReportService reports) {
    this.reports = reports;
  }

  @TransactionalEventListener(fallbackExecution = true)
  void on(UserWithdrawn event) {
    reports.forget(event.userId());
  }
}
