package kr.mujuthaek.api.chat.event;

import kr.mujuthaek.api.chat.service.ChatService;
import kr.mujuthaek.api.common.event.UserWithdrawn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 회원이 탈퇴하면(커밋 뒤) 그 회원의 채팅 데이터를 지워요 (요구사항 3-16: 탈퇴 후 데이터 0건, notice·area 제보와 같은 방식). */
@Component
class ChatWithdrawalListener {

  private final ChatService chats;

  ChatWithdrawalListener(ChatService chats) {
    this.chats = chats;
  }

  @TransactionalEventListener(fallbackExecution = true)
  void on(UserWithdrawn event) {
    chats.forget(event.userId());
  }
}
