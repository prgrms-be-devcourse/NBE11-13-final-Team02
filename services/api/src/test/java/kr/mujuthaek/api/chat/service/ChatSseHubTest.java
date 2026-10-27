package kr.mujuthaek.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import kr.mujuthaek.api.chat.event.ChatEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 방별 연결: 붙이고, 끊긴 연결로 보내거나 하트비트를 보내도 예외가 밖으로 나오지 않아요. 실제 전달은 ChatApiTest에서 봐요. */
class ChatSseHubTest {

  private final ChatSseHub hub = new ChatSseHub();

  @Test
  void subscribeJoinsTheRoom() {
    UUID room = UUID.randomUUID();

    hub.subscribe(room);
    hub.subscribe(room);

    assertThat(hub.connections(room)).isEqualTo(2);
    assertThat(hub.connections(UUID.randomUUID())).isZero();
  }

  @Test
  void closedConnectionsDoNotBreakBroadcastOrHeartbeat() {
    UUID room = UUID.randomUUID();
    SseEmitter emitter = hub.subscribe(room);
    emitter.complete();

    assertThatCode(
            () -> {
              hub.broadcast(ChatEvent.deleted(room, UUID.randomUUID()));
              hub.broadcast(ChatEvent.likeUpdated(UUID.randomUUID(), UUID.randomUUID(), 1));
              hub.heartbeat();
            })
        .doesNotThrowAnyException();
  }
}
