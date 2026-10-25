package kr.mujuthaek.api.chat.event;

import kr.mujuthaek.api.chat.service.ChatService;
import kr.mujuthaek.api.common.event.UserWithdrawn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 회원 탈퇴가 커밋된 뒤 그 회원의 채팅 데이터를 지워요. */
@Component
class UserWithdrawnListener {

  private final ChatService chats;

  UserWithdrawnListener(ChatService chats) {
    this.chats = chats;
  }

  @Async
  @TransactionalEventListener(fallbackExecution = true)
  void on(UserWithdrawn event) {
    chats.forget(event.userId());
  }
}
