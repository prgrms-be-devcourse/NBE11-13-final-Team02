package kr.mujuthaek.api.chat.event;

import kr.mujuthaek.api.chat.service.ChatSseHub;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋된 채팅 변경만 방의 SSE 연결로 보내요. */
@Component
class ChatEventRelay {

  private final ChatSseHub hub;

  ChatEventRelay(ChatSseHub hub) {
    this.hub = hub;
  }

  @TransactionalEventListener
  void on(ChatEvent event) {
    hub.broadcast(event);
  }
}
